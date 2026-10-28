package com.deepfaceswap.android

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import java.io.File
import java.io.FileOutputStream

/**
 * Four frames from across a clip, swapped, before committing to the whole thing.
 *
 * The samples are deliberately **not** the first four frames. The opening of a
 * clip is usually the calmest part - the subject is still, facing the camera, and
 * nothing is in the way. The failures happen later: a hand comes up, the head
 * turns, the light changes. Four frames from the start will happily look fine on
 * a clip that falls apart at second eight, which is exactly the render you would
 * rather not have waited for.
 *
 * So the frames come from 5%, 35%, 65% and 95% of the duration.
 *
 * Each frame is swapped through the ordinary image path - the same call the Face
 * Swap screen makes - rather than through the video pipeline. That keeps this
 * feature entirely outside the code that produces the real output, so a preview
 * can never corrupt or slow the thing it is meant to predict.
 */
object VideoPreview {

    private val SAMPLES = floatArrayOf(0.05f, 0.35f, 0.65f, 0.95f)

    /**
     * Extract, swap and return the sample frames. Empty when the clip cannot be
     * read or has no readable frames.
     */
    fun build(
        context: Context,
        input: File,
        sourcePaths: List<String>,
        swapperFile: String,
        pixelBoost: Int,
        enhancerStrength: Float,
        onMessage: (String) -> Unit,
    ): List<File> {
        val dir = File(context.cacheDir, "preview").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }

        val retriever = MediaMetadataRetriever()
        val raw = mutableListOf<File>()
        try {
            retriever.setDataSource(input.absolutePath)
            val durationMs = retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L
            if (durationMs <= 0L) return emptyList()

            SAMPLES.forEachIndexed { i, fraction ->
                onMessage("Reading frame ${i + 1} of ${SAMPLES.size} \u2026")
                val atUs = (durationMs * 1000.0 * fraction).toLong()
                val bitmap: Bitmap = retriever.getFrameAtTime(
                    atUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                ) ?: return@forEachIndexed
                val file = File(dir, "frame_${i + 1}.jpg")
                FileOutputStream(file).use { out ->
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 92, out)
                }
                bitmap.recycle()
                raw.add(file)
            }
        } catch (_: Throwable) {
            return emptyList()
        } finally {
            runCatching { retriever.release() }
        }

        if (raw.isEmpty()) return emptyList()

        val swapped = mutableListOf<File>()
        raw.forEachIndexed { i, frame ->
            onMessage("Swapping frame ${i + 1} of ${raw.size} \u2026")
            val out = File(dir, "preview_${i + 1}.jpg")
            val ok = runCatching {
                NativeBridge.swap(
                    context, sourcePaths, frame.absolutePath, out.absolutePath,
                    swapperFile, false, false, pixelBoost, enhancerStrength,
                ).ok
            }.getOrDefault(false)
            if (ok && out.isFile && out.length() > 0L) swapped.add(out) else swapped.add(frame)
        }
        return swapped
    }
}
