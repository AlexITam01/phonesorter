package com.photosorter.app

import android.content.Context
import android.graphics.Bitmap
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

/**
 * Local NSFW classifier — OPTIONAL, model not bundled.
 *
 * Honest limitation (see README): there is no small, reliable,
 * openly-licensed TFLite model for futanari / explicit-vs-suggestive
 * detection that can be bundled cleanly in this repo. NudeNet (the
 * model used by the PC script) is a PyTorch/ONNX model, not a TFLite
 * model, and converting it is a separate, unverified step.
 *
 * What this class does:
 *  1. If the user places a compatible TFLite model at
 *     assets/nsfw.tflite (single float output, 0.0 = safe,
 *     1.0 = explicit, input 224x224 RGB, e.g. a converted
 *     OpenNSFW2 / nsfwjs-style classifier), it runs it on-device.
 *  2. Otherwise it returns [NsfwResult.Unknown] and the app falls
 *     back to: heuristics + ML Kit labels + a user review queue.
 *     Nothing is ever auto-deleted, and nothing is ever labelled
 *     nsfw_explicit on heuristics alone — that needs the model
 *     or a human tap.
 *
 * Never use this class's output as proof an image is safe. It is a
 * triage signal only.
 */
class NsfwClassifier(context: Context) {

    sealed interface NsfwResult {
        /** Model ran: score 0..1, higher = more likely explicit. */
        data class Scored(val score: Float) : NsfwResult
        /** No model bundled / model failed to load. */
        data object Unknown : NsfwResult
    }

    private var interpreter: Interpreter? = null

    init {
        try {
            context.assets.openFd("nsfw.tflite").use { fd ->
                val input = FileInputStream(fd.fileDescriptor)
                val channel = input.channel
                val buffer: MappedByteBuffer = channel.map(
                    FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength
                )
                interpreter = Interpreter(buffer)
            }
        } catch (_: Exception) {
            // Expected in v0.1.0: no model bundled. Fall back to review queue.
            interpreter = null
        }
    }

    fun hasModel(): Boolean = interpreter != null

    /** Runs the bundled model on a 224x224 thumbnail, or Unknown. */
    fun score(thumbnail: Bitmap): NsfwResult {
        val interp = interpreter ?: return NsfwResult.Unknown
        return try {
            val scaled = Bitmap.createScaledBitmap(thumbnail, 224, 224, true)
            val input = Array(1) { Array(224) { Array(224) { FloatArray(3) } } }
            val pixels = IntArray(224 * 224)
            scaled.getPixels(pixels, 0, 224, 0, 0, 224, 224)
            for (i in pixels.indices) {
                val p = pixels[i]
                val y = i / 224; val x = i % 224
                input[0][y][x][0] = ((p shr 16) and 0xFF) / 255f
                input[0][y][x][1] = ((p shr 8) and 0xFF) / 255f
                input[0][y][x][2] = (p and 0xFF) / 255f
            }
            val output = Array(1) { FloatArray(1) }
            interp.run(input, output)
            NsfwResult.Scored(output[0][0].coerceIn(0f, 1f))
        } catch (_: Exception) {
            NsfwResult.Unknown
        }
    }

    fun close() {
        try { interpreter?.close() } catch (_: Exception) { }
        interpreter = null
    }
}
