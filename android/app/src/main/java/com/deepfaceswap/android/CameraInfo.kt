package com.deepfaceswap.android

import android.content.Context
import android.media.ExifInterface
import java.io.File

/**
 * What the camera told us, and what can be worked out from it.
 *
 * This exists because of a flaw in the obvious approach to facial proportions:
 * if the source and target were shot on different lenses, the geometry in the
 * two files genuinely differs even for the same face. Phone front cameras are
 * 24-26mm equivalent, and wide angle at arm's length enlarges the nose and
 * narrows the jaw. Comparing ratios across those two images measures the lens,
 * not the person.
 *
 * Everything here is read from EXIF where it exists and estimated where it does
 * not, and every estimate says so. Screenshots and messaging apps strip EXIF
 * routinely, so absence is normal rather than exceptional.
 */
data class CameraInfo(
    val focalMm: Float?,
    val focal35mm: Float?,
    val fNumber: Float?,
    val iso: Int?,
    val exposureSeconds: Double?,
    val make: String?,
    val model: String?,
    val width: Int,
    val height: Int,
) {

    /** True when there is enough here to say anything about perspective. */
    val hasLens: Boolean get() = focal35mm != null && focal35mm > 0f

    /**
     * Radial distortion coefficient, estimated from how wide the lens is.
     *
     * There is no per-device profile available, so this is a shape-based guess:
     * phone ultrawides are visibly barrel-distorted, 50mm-and-up lenses are
     * essentially rectilinear. It is reported as an estimate and never used
     * silently.
     */
    val k1: Float
        get() {
            val f = focal35mm ?: return 0f
            return when {
                f >= 50f -> 0f
                f >= 40f -> 0.015f
                f >= 30f -> 0.035f
                f >= 26f -> 0.06f
                else -> 0.085f
            }
        }

    /**
     * Subject distance in metres, from the face's interpupillary distance.
     *
     *   D = f_px * IPD_mm / IPD_px
     *
     * The 35mm-equivalent focal length is converted to pixels using the diagonal
     * of a 36x24 frame, which is how "equivalent" is defined. Adult IPD is taken
     * as 63mm; that is a population average and the result is an estimate.
     */
    fun distanceMetres(interpupillaryPx: Float): Float? {
        val f35 = focal35mm ?: return null
        if (interpupillaryPx <= 1f || width <= 0 || height <= 0) return null
        // Pixels per millimetre of the 36x24 reference frame.
        val diagonalPx = Math.hypot(width.toDouble(), height.toDouble()).toFloat()
        val diagonalMm = Math.hypot(36.0, 24.0).toFloat()
        val pxPerMm = diagonalPx / diagonalMm
        val fPx = f35 * pxPerMm
        return (fPx * IPD_MM / interpupillaryPx) / 1000f
    }

    /** Horizontal field of view in degrees, when the lens is known. */
    val horizontalFovDegrees: Float?
        get() {
            val f = focal35mm ?: return null
            return (2.0 * Math.toDegrees(Math.atan(36.0 / (2.0 * f)))).toFloat()
        }

    companion object {
        const val IPD_MM = 63f

        fun read(file: File): CameraInfo? {
            if (!file.isFile) return null
            val exif = runCatching { ExifInterface(file.absolutePath) }.getOrNull()
            val opts = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            runCatching { android.graphics.BitmapFactory.decodeFile(file.absolutePath, opts) }

            fun float(tag: String): Float? =
                exif?.getAttribute(tag)?.toFloatOrNull()?.takeIf { it > 0f }

            return CameraInfo(
                focalMm = float(ExifInterface.TAG_FOCAL_LENGTH),
                focal35mm = float(ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM),
                fNumber = float(ExifInterface.TAG_F_NUMBER),
                iso = exif?.getAttribute("PhotographicSensitivity")?.toIntOrNull(),
                exposureSeconds = exif?.getAttribute(ExifInterface.TAG_EXPOSURE_TIME)?.toDoubleOrNull(),
                make = exif?.getAttribute(ExifInterface.TAG_MAKE),
                model = exif?.getAttribute(ExifInterface.TAG_MODEL),
                width = opts.outWidth.coerceAtLeast(0),
                height = opts.outHeight.coerceAtLeast(0),
            )
        }
    }
}
