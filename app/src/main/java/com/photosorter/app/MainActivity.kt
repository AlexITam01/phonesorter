package com.photosorter.app

import android.Manifest
import android.app.Activity
import android.content.ContentUris
import android.content.ContentValues
import android.content.IntentSender
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
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.photosorter.app.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Photo Sorter — local only.
 *
 * Flow:
 *  1. Scan: read MediaStore image list (permission READ_MEDIA_IMAGES).
 *  2. For each photo, make a thumbnail (max 384 px) on-device,
 *     classify it on-device, write plan.csv / plan.json first.
 *  3. Results grouped by category with counts. Tap a category to
 *     see its thumbnails. Nothing moves or deletes until you tap
 *     "Approve move" or "Approve delete" for that category.
 *  4. Approved moves copy to Pictures/Sorted/<category>/, then
 *     ask Android to delete the original (system confirmation).
 *     Approved deletes ask Android to delete, with no copy.
 *
 * No internet permission, no upload, no account.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var classifier: Classifier
    private var items: List<PhotoItem> = emptyList()
    private var selectedCategory: String? = null

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) startScan() else toast("Photo permission is needed to scan.")
    }

    private val deleteLauncher = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) toast("Approved action completed.")
        else toast("Delete/move was not confirmed — nothing was deleted.")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        classifier = Classifier(this)

        binding.btnScan.setOnClickListener { requestPermissionAndScan() }
        binding.btnExportPlan.setOnClickListener {
            if (items.isEmpty()) { toast("Scan first."); return@setOnClickListener }
            PlanStore.writePlan(this, items)
            toast("Plan saved: plan.csv / plan.json in app files.")
        }
        binding.tvPrivacy.text = getString(R.string.privacy_notice)
        binding.rvCategories.layoutManager = LinearLayoutManager(this)
        binding.rvPhotos.layoutManager = GridLayoutManager(this, 3)
        updateSummary()
    }

    override fun onDestroy() {
        classifier.close()
        super.onDestroy()
    }

    private fun requestPermissionAndScan() {
        val perm = if (Build.VERSION.SDK_INT >= 33)
            Manifest.permission.READ_MEDIA_IMAGES
        else Manifest.permission.READ_EXTERNAL_STORAGE
        if (ContextCompat.checkSelfPermission(this, perm) == PackageManager.PERMISSION_GRANTED) {
            startScan()
        } else {
            permissionLauncher.launch(perm)
        }
    }

    private fun startScan() {
        binding.btnScan.isEnabled = false
        binding.tvProgress.text = "Scanning MediaStore…"
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val found = queryPhotos()
                val classified = mutableListOf<PhotoItem>()
                val seenHashes = HashSet<String>()
                for ((index, item) in found.withIndex()) {
                    val thumb = loadThumbnail(item) ?: continue
                    val small = classifier.thumbnail(thumb, 384)
                    val hash = classifier.thumbHash(small)
                    if (!seenHashes.add(hash)) {
                        classified.add(item.copy(category = "duplicate_near_duplicate",
                            confidence = 0.6f, caption = "Same thumbnail as an earlier photo",
                            needsReview = true, thumbHash = hash))
                    } else {
                        classified.add(classifier.classify(item.copy(thumbHash = hash), small))
                    }
                    if (thumb != small) thumb.recycle()
                    small.recycle()
                    if (index % 10 == 0) withContext(Dispatchers.Main) {
                        binding.tvProgress.text = "Classified ${index + 1} / ${found.size}…"
                    }
                }
                PlanStore.writePlan(this@MainActivity, classified)
                withContext(Dispatchers.Main) {
                    items = classified
                    binding.btnScan.isEnabled = true
                    binding.tvProgress.text =
                        "Done: ${classified.size} photos. Plan saved (plan.csv / plan.json)."
                    updateSummary()
                    toast("Scan done. Review categories before approving anything.")
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    binding.btnScan.isEnabled = true
                    binding.tvProgress.text = "Scan failed: ${e.message}"
                }
            }
        }
    }

    private fun queryPhotos(): List<PhotoItem> {
        val out = mutableListOf<PhotoItem>()
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.SIZE,
            MediaStore.Images.Media.DATE_TAKEN,
            MediaStore.Images.Media.WIDTH,
            MediaStore.Images.Media.HEIGHT
        )
        contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            projection, null, null,
            "${MediaStore.Images.Media.DATE_TAKEN} DESC"
        )?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
            val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE)
            val dateCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_TAKEN)
            val wCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.WIDTH)
            val hCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.HEIGHT)
            while (cursor.moveToNext()) {
                val id = cursor.getLong(idCol)
                out.add(PhotoItem(
                    id = id,
                    uri = ContentUris.withAppendedId(
                        MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id),
                    displayName = cursor.getString(nameCol) ?: "photo_$id",
                    sizeBytes = cursor.getLong(sizeCol),
                    dateTakenMs = cursor.getLong(dateCol),
                    width = cursor.getInt(wCol),
                    height = cursor.getInt(hCol)
                ))
            }
        }
        return out
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

    private fun updateSummary() {
        val grouped = items.groupBy { it.category }
        binding.rvCategories.adapter = CategoryAdapter(grouped) { category ->
            selectedCategory = category
            val photos = grouped[category].orEmpty()
            binding.tvSelected.text = "$category — ${photos.size} photos (tap Approve to act)"
            binding.rvPhotos.adapter = PhotoAdapter(photos)
        }
        val nsfwCount = items.count { Categories.isNsfw(it.category) }
        val reviewCount = items.count { it.needsReview }
        binding.tvSummary.text =
            "Photos: ${items.size}  •  NSFW-flagged: $nsfwCount  •  Needs review: $reviewCount\n" +
            "NSFW model bundled: ${classifier.hasNsfwModel()} " +
            "(false = heuristics + review queue, see README)"
    }

    // ---- Approved actions (per category, only after user taps) ----

    private fun approveMove(category: String) {
        val photos = items.filter { it.category == category }
        if (photos.isEmpty()) return
        AlertDialog.Builder(this)
            .setTitle("Move ${photos.size} photos?")
            .setMessage("Copy to Pictures/Sorted/${Categories.folderName(category)}/ " +
                "and then ask Android to delete the originals. " +
                "Nothing is deleted without Android's confirmation.")
            .setPositiveButton("Approve move") { _, _ -> doMove(photos, category) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun approveDelete(category: String) {
        val photos = items.filter { it.category == category }
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

    private fun doMove(photos: List<PhotoItem>, category: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            val folder = Categories.folderName(category)
            var copied = 0
            for (item in photos) {
                try {
                    val values = ContentValues().apply {
                        put(MediaStore.Images.Media.DISPLAY_NAME, item.displayName)
                        put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                        put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Sorted/$folder")
                    }
                    val dest = contentResolver.insert(
                        MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: continue
                    contentResolver.openInputStream(item.uri)?.use { input ->
                        contentResolver.openOutputStream(dest)?.use { out ->
                            input.copyTo(out)
                        }
                    }
                    copied++
                } catch (_: Exception) { /* keep going; user reviews failures */ }
            }
            withContext(Dispatchers.Main) {
                toast("Copied $copied / ${photos.size} to Pictures/Sorted/$folder. " +
                    "Now confirm deletion of originals.")
                requestDelete(photos.map { it.uri })
            }
        }
    }

    private fun requestDelete(uris: List<Uri>) {
        if (uris.isEmpty()) return
        try {
            val intentSender: IntentSender =
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

    inner class PhotoAdapter(private val photos: List<PhotoItem>) :
        RecyclerView.Adapter<PhotoAdapter.Holder>() {
        inner class Holder(v: View) : RecyclerView.ViewHolder(v) {
            val img: ImageView = v.findViewById(R.id.ivThumb)
            val tv: TextView = v.findViewById(R.id.tvLabel)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val v = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_photo, parent, false)
            return Holder(v)
        }

        override fun getItemCount() = photos.size

        override fun onBindViewHolder(h: Holder, position: Int) {
            val item = photos[position]
            // Show the on-device thumbnail only; never a full photo.
            lifecycleScope.launch(Dispatchers.IO) {
                val thumb = loadThumbnail(item)
                withContext(Dispatchers.Main) {
                    if (thumb != null) h.img.setImageBitmap(thumb)
                }
            }
            h.tv.text = if (Categories.isExplicit(item.category)) "[redacted]"
                        else item.displayName
            h.itemView.setOnClickListener {
                // Tap to reassign category by hand — the reliable path
                // for futanari / meme / travel, where the model is weak.
                val cats = Categories.ALL.toTypedArray()
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("Reassign: ${item.displayName}")
                    .setItems(cats) { _, which ->
                        val updated = items.map {
                            if (it.id == item.id) it.copy(
                                category = cats[which], needsReview = false,
                                caption = if (Categories.isExplicit(cats[which])) "[redacted]"
                                          else "Reassigned by user")
                            else it
                        }
                        items = updated
                        PlanStore.writePlan(this@MainActivity, items)
                        updateSummary()
                    }
                    .show()
            }
        }
    }
}
