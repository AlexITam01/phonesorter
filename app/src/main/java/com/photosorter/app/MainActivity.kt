package com.photosorter.app

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.Size
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.photosorter.app.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Photo Sorter v0.2.0 — local only.
 *
 * Flow:
 *  1. Scan: ViewModel reads MediaStore, makes a thumbnail (max 384 px)
 *     on-device, classifies it on-device, writes plan.csv / plan.json
 *     first (checkpointed every 10 photos).
 *  2. Results grouped by category with counts. Tap a category to
 *     see its thumbnails. Tap a thumbnail to reassign it; tap Deny
 *     on a needs-review photo to move it to "other" in the plan.
 *  3. Nothing moves or deletes until you tap "Approve move" or
 *     "Approve delete" for a category.
 *  4. Approved moves copy to Pictures/Sorted/<category>/, then
 *     ask Android to delete the original (system confirmation).
 *
 * v0.2 crash / lifecycle fixes (see README):
 *  - Scan/move run in SorterViewModel (viewModelScope), NOT in the
 *    Activity's lifecycleScope — rotation no longer cancels them,
 *    and the Classifier is closed in ViewModel.onCleared(), not in
 *    Activity.onDestroy().
 *  - All progress goes through StateFlow observed on the Main thread;
 *    no View is ever touched from Dispatchers.IO.
 *  - Dialogs and the delete launcher are guarded with
 *    isFinishing / isDestroyed checks.
 *  - Per-photo thumbnails in the grid are loaded with a tag check
 *    (holder recycled -> bitmap is not set on the wrong photo) and
 *    a recycled-bitmap guard.
 *  - The plan is checkpointed every 10 photos, so if the app is
 *    killed, "Resume from saved plan" restores the last checkpoint.
 *    A force-kill still stops in-progress work — no WorkManager
 *    is implemented in v0.2.0, and that limit is documented.
 *
 * No internet permission, no upload, no account.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var viewModel: SorterViewModel
    private var selectedCategory: String? = null

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) viewModel.startScan() else toast("Photo permission is needed to scan.")
    }

    private val deleteLauncher = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (isFinishing || isDestroyed) return@registerForActivityResult
        if (result.resultCode == Activity.RESULT_OK) toast("Approved action completed.")
        else toast("Delete/move was not confirmed — nothing was deleted.")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        viewModel = ViewModelProvider(this)[SorterViewModel::class.java]
        selectedCategory = savedInstanceState?.getString("selected_category")

        binding.btnScan.setOnClickListener { requestPermissionAndScan() }
        binding.btnResume.setOnClickListener {
            if (viewModel.resumeFromSavedPlan()) {
                toast("Resumed from saved plan.")
            } else {
                toast("No saved plan to resume — scan first.")
            }
        }
        binding.btnExportPlan.setOnClickListener {
            if (viewModel.items.value.isEmpty()) {
                toast("Scan first.")
                return@setOnClickListener
            }
            viewModel.exportPlan()
            toast("Plan saved: plan.csv / plan.json in app files.")
        }
        binding.tvPrivacy.text = getString(R.string.privacy_notice)
        binding.rvCategories.layoutManager = LinearLayoutManager(this)
        binding.rvPhotos.layoutManager = GridLayoutManager(this, 3)

        // Observe ViewModel state on the Main thread only.
        lifecycleScope.launch {
            viewModel.items.collect { updateSummary() }
        }
        lifecycleScope.launch {
            viewModel.progressLabel.collect { label ->
                binding.tvProgress.text = label
            }
        }
        lifecycleScope.launch {
            viewModel.progressDone.collect { done ->
                binding.pbProgress.progress = done
            }
        }
        lifecycleScope.launch {
            viewModel.progressTotal.collect { total ->
                // ProgressBar max must be >= 1; 0 total would hide the bar.
                binding.pbProgress.max = total.coerceAtLeast(1)
            }
        }
        lifecycleScope.launch {
            viewModel.progressVisible.collect { visible ->
                binding.pbProgress.visibility = if (visible) View.VISIBLE else View.GONE
            }
        }
        lifecycleScope.launch {
            viewModel.isScanning.collect { scanning ->
                binding.btnScan.isEnabled = !scanning
                binding.btnResume.isEnabled = !scanning && viewModel.hasSavedPlan()
            }
        }
        binding.btnResume.isEnabled = viewModel.hasSavedPlan()
        updateSummary()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString("selected_category", selectedCategory)
    }

    // NOTE: no onDestroy() classifier cleanup here — that was a v0.1
    // crash cause (rotation closed the Classifier mid-scan).
    // The ViewModel closes it in onCleared().

    private fun requestPermissionAndScan() {
        val perm = if (Build.VERSION.SDK_INT >= 33)
            Manifest.permission.READ_MEDIA_IMAGES
        else Manifest.permission.READ_EXTERNAL_STORAGE
        if (ContextCompat.checkSelfPermission(this, perm) == PackageManager.PERMISSION_GRANTED) {
            viewModel.startScan()
        } else {
            permissionLauncher.launch(perm)
        }
    }

    private fun updateSummary() {
        val items = viewModel.items.value
        val grouped = items.groupBy { it.category }
        binding.rvCategories.adapter = CategoryAdapter(grouped) { category ->
            selectedCategory = category
            val photos = grouped[category].orEmpty()
            binding.tvSelected.text = "$category — ${photos.size} photos (tap Approve to act)"
            binding.rvPhotos.adapter = PhotoAdapter(
                photos,
                onDeny = { photo -> viewModel.deny(photo) },
                onPhotoClick = { photo -> showReassignDialog(photo) }
            )
        }
        // Keep the photo grid in sync after a Deny/reassign changes
        // the selected category's contents.
        selectedCategory?.let { cat ->
            val photos = grouped[cat].orEmpty()
            if (binding.rvPhotos.adapter is PhotoAdapter) {
                binding.tvSelected.text = "$cat — ${photos.size} photos (tap Approve to act)"
                binding.rvPhotos.adapter = PhotoAdapter(
                    photos,
                    onDeny = { photo -> viewModel.deny(photo) },
                    onPhotoClick = { photo -> showReassignDialog(photo) }
                )
            }
        }
        val nsfwCount = items.count { Categories.isNsfw(it.category) }
        val reviewCount = items.count { it.needsReview }
        binding.tvSummary.text =
            "Photos: ${items.size}  •  NSFW-flagged: $nsfwCount  •  Needs review: $reviewCount\n" +
            "NSFW model bundled: ${viewModel.hasNsfwModel()} " +
            "(false = heuristics + review queue, see README)"
    }

    private fun showReassignDialog(photo: PhotoItem) {
        if (isFinishing || isDestroyed) return
        val cats = Categories.ALL.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Reassign: ${photo.displayName}")
            .setItems(cats) { _, which ->
                viewModel.reassign(photo, cats[which])
            }
            .show()
    }

    // ---- Approved actions (per category, only after user taps) ----

    private fun approveMove(category: String) {
        if (isFinishing || isDestroyed) return
        val photos = viewModel.items.value.filter { it.category == category }
        if (photos.isEmpty()) return
        AlertDialog.Builder(this)
            .setTitle("Move ${photos.size} photos?")
            .setMessage("Copy to Pictures/Sorted/${Categories.folderName(category)}/ " +
                "and then ask Android to delete the originals. " +
                "Nothing is deleted without Android's confirmation.")
            .setPositiveButton("Approve move") { _, _ ->
                viewModel.moveCategory(photos, category) { copied, total, folder ->
                    if (isFinishing || isDestroyed) return@moveCategory
                    toast("Copied $copied / $total to Pictures/Sorted/$folder. " +
                        "Now confirm deletion of originals.")
                    requestDelete(photos.map { it.uri })
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun approveDelete(category: String) {
        if (isFinishing || isDestroyed) return
        val photos = viewModel.items.value.filter { it.category == category }
        if (photos.isEmpty()) return
        AlertDialog.Builder(this)
            .setTitle("Delete ${photos.size} photos?")
            .setMessage("This asks Android to delete these photos from the phone. " +
                "They go to the system trash / Photos trash first where possible. " +
                "This cannot be undone from this app.")
            .setPositiveButton("Approve delete") { _, _ -> requestDelete(photos.map { it.uri }) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun requestDelete(uris: List<Uri>) {
        if (uris.isEmpty() || isFinishing || isDestroyed) return
        try {
            val intentSender: android.content.IntentSender =
                MediaStore.createDeleteRequest(contentResolver, uris).intentSender
            deleteLauncher.launch(IntentSenderRequest.Builder(intentSender).build())
        } catch (e: Exception) {
            toast("Delete request failed: ${e.message}")
        }
    }

    private fun toast(msg: String) =
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    // ---- Adapters ----

    inner class CategoryAdapter(
        private val grouped: Map<String, List<PhotoItem>>,
        private val onSelect: (String) -> Unit
    ) : RecyclerView.Adapter<CategoryAdapter.Holder>() {
        private val entries = Categories.ALL
            .map { it to (grouped[it]?.size ?: 0) }
            .filter { it.second > 0 } +
            grouped.keys.filter { it !in Categories.ALL }
                .map { it to grouped[it]!!.size }

        inner class Holder(v: View) : RecyclerView.ViewHolder(v) {
            val tv: TextView = v.findViewById(R.id.tvCategory)
            val btnMove: View = v.findViewById(R.id.btnMove)
            val btnDelete: View = v.findViewById(R.id.btnDelete)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val v = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_category, parent, false)
            return Holder(v)
        }

        override fun getItemCount() = entries.size

        override fun onBindViewHolder(h: Holder, position: Int) {
            val (cat, count) = entries[position]
            val review = grouped[cat]?.count { it.needsReview } ?: 0
            h.tv.text = "$cat — $count" + if (review > 0) " ($review need review)" else ""
            h.tv.setOnClickListener { onSelect(cat) }
            h.btnMove.setOnClickListener { approveMove(cat) }
            h.btnDelete.setOnClickListener { approveDelete(cat) }
        }
    }

    inner class PhotoAdapter(
        private val photos: List<PhotoItem>,
        private val onDeny: (PhotoItem) -> Unit,
        private val onPhotoClick: (PhotoItem) -> Unit
    ) : RecyclerView.Adapter<PhotoAdapter.Holder>() {
        inner class Holder(v: View) : RecyclerView.ViewHolder(v) {
            val img: ImageView = v.findViewById(R.id.ivThumb)
            val tv: TextView = v.findViewById(R.id.tvLabel)
            val btnDeny: Button = v.findViewById(R.id.btnDeny)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val v = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_photo, parent, false)
            return Holder(v)
        }

        override fun getItemCount() = photos.size

        override fun onBindViewHolder(h: Holder, position: Int) {
            val item = photos[position]
            h.img.tag = item.id
            h.img.setImageBitmap(null)
            // Show the on-device thumbnail only; never a full photo.
            // Guard: if the holder was recycled for another photo
            // while the thumbnail loaded, do NOT set the bitmap —
            // that was a v0.1 wrong-photo / crash path.
            lifecycleScope.launch(Dispatchers.IO) {
                val thumb = loadThumbnail(item)
                withContext(Dispatchers.Main) {
                    if (isFinishing || isDestroyed) return@withContext
                    if (h.img.tag != item.id) return@withContext
                    if (thumb != null && !thumb.isRecycled) {
                        h.img.setImageBitmap(thumb)
                    }
                }
            }
            h.tv.text = if (Categories.isExplicit(item.category)) "[redacted]"
                        else item.displayName
            // Deny: only for needs-review photos. Tapping it moves
            // the photo to "other" in the plan — it does NOT delete
            // or move the file. See SorterViewModel.deny().
            h.btnDeny.visibility = if (item.needsReview) View.VISIBLE else View.GONE
            h.btnDeny.setOnClickListener { onDeny(item) }
            h.itemView.setOnClickListener { onPhotoClick(item) }
        }
    }

    private fun loadThumbnail(item: PhotoItem): Bitmap? {
        return try {
            if (Build.VERSION.SDK_INT >= 29) {
                contentResolver.loadThumbnail(item.uri, Size(384, 384), null)
            } else {
                contentResolver.openInputStream(item.uri)?.use { input ->
                    BitmapFactory.decodeStream(input)
                }
            }
        } catch (_: Exception) { null }
    }
}
