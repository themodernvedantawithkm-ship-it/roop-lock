package com.deepfaceswap.android

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Camera and head geometry recovered from the pixels, when the file carries no
 * EXIF to say so.
 *
 * Screenshots and images that have been through a messaging app routinely arrive
 * with no camera data at all - which was the case in the report that prompted
 * this. Rather than give up and print "unknown", the five facial keypoints and a
 * canonical 3D face are enough to solve for the focal length and the head pose
 * together. That is ordinary photogrammetry: five known 3D points and their
 * projections over-determine rotation, translation and focal length.
 *
 * The solve is a weak-perspective fit followed by a one-dimensional search over
 * focal length, chosen to minimise reprojection error. The residual is reported,
 * because an estimate without an error bar is a guess wearing a suit.
 */
object Perspective {

    /**
     * A canonical adult face, in millimetres.
     *
     * x right, y down, z toward the camera. Interpupillary 63mm, eye line to nose
     * tip 30mm, mouth 65mm below the eyes and 50mm wide - population averages,
     * so the result is an estimate rather than a measurement.
     */
    private val CANON_3D = arrayOf(
        doubleArrayOf(-31.5, 0.0, 0.0),   // left eye
        doubleArrayOf(31.5, 0.0, 0.0),    // right eye
        doubleArrayOf(0.0, 30.0, 25.0),   // nose tip
        doubleArrayOf(-25.0, 65.0, 5.0),  // left mouth corner
        doubleArrayOf(25.0, 65.0, 5.0),   // right mouth corner
    )

    /** How the file got here, which decides how much the numbers can be trusted. */
    enum class Source { CAMERA_EXIF, INFERRED, SCREENSHOT, UNKNOWN }

    data class Estimate(
        val source: Source,
        val focalPx: Float,
        val focal35mm: Float,
        val yaw: Float,
        val pitch: Float,
        val roll: Float,
        /** Mean reprojection error in pixels - how well the model fits. */
        val residualPx: Float,
        val distanceMetres: Float?,
    ) {
        /**
         * How much to trust the solve.
         *
         * Under about 3px the fit is good; past 8px the landmarks and the model
         * disagree badly enough that the pose is only a rough indication.
         */
        val confidence: String
            get() = when {
                residualPx <= 3f -> "good"
                residualPx <= 8f -> "fair"
                else -> "poor"
            }
    }

    /**
     * Decide where the file came from.
     *
     * Screenshots are worth calling out separately: they have no lens, no
     * perspective and no camera, so every geometric figure derived from one
     * describes a picture of a photograph rather than a photograph.
     */
    fun classify(file: java.io.File, camera: CameraInfo?): Source {
        val hasLens = camera?.focal35mm != null && camera.focal35mm > 0f
        if (hasLens) return Source.CAMERA_EXIF
        val name = file.name.lowercase()
        if (name.contains("screenshot") || name.contains("screen_")) return Source.SCREENSHOT
        // A screenshot is nearly always a PNG whose dimensions are a known
        // display resolution, and it carries no capture tags at all.
        if (name.endsWith(".png") && camera != null && camera.focalMm == null &&
            isScreenResolution(camera.width, camera.height)
        ) {
            return Source.SCREENSHOT
        }
        return Source.INFERRED
    }

    private val SCREEN_SIZES = setOf(
        1080 to 2400, 1080 to 2340, 1080 to 2376, 1080 to 2412, 1170 to 2532,
        1284 to 2778, 1440 to 3200, 1440 to 3168, 1440 to 3120, 720 to 1600,
        750 to 1334, 1125 to 2436, 828 to 1792, 1242 to 2688, 1290 to 2796,
    )

    private fun isScreenResolution(w: Int, h: Int): Boolean {
        if (w <= 0 || h <= 0) return false
        return (w to h) in SCREEN_SIZES || (h to w) in SCREEN_SIZES
    }

    /**
     * Solve focal length and pose from the five keypoints.
     *
     * Returns null when the fit is impossible - the caller then falls back to
     * reporting nothing rather than inventing a number.
     */
    fun solve(
        landmarks: FaceMath.Landmarks,
        imageWidth: Int,
        imageHeight: Int,
        source: Source,
    ): Estimate? {
        if (imageWidth <= 0 || imageHeight <= 0) return null
        val pts = landmarks.points
        if (pts.size < 5) return null

        val cx = imageWidth / 2.0
        val cy = imageHeight / 2.0

        // Centre both sets.
        val meanX = pts.map { it.first.toDouble() }.average()
        val meanY = pts.map { it.second.toDouble() }.average()
        val obs = pts.map { doubleArrayOf(it.first - meanX, it.second - meanY) }

        val modelMean = DoubleArray(3)
        CANON_3D.forEach { p -> for (i in 0..2) modelMean[i] += p[i] / CANON_3D.size }
        val model = CANON_3D.map { p -> doubleArrayOf(p[0] - modelMean[0], p[1] - modelMean[1], p[2] - modelMean[2]) }

        // Weak-perspective fit: find the 2x3 matrix M minimising ||M X - x||.
        // M = (sum x X^T) (sum X X^T)^-1, a small linear solve.
        val sxx = Array(3) { DoubleArray(3) }
        val sx = Array(2) { DoubleArray(3) }
        for (i in 0..4) {
            for (a in 0..2) for (b in 0..2) sxx[a][b] += model[i][a] * model[i][b]
            for (a in 0..2) {
                sx[0][a] += obs[i][0] * model[i][a]
                sx[1][a] += obs[i][1] * model[i][a]
            }
        }
        val inv = invert3(sxx) ?: return null
        val m = Array(2) { r -> DoubleArray(3) { c -> (0..2).sum { k -> sx[r][k] * inv[k][c] } } }

        val scale = (norm(m[0]) + norm(m[1])) / 2.0
        if (scale <= 1e-6) return null

        // Rotation rows from the two fitted rows; the third is their cross product.
        val r1 = m[0].map { it / scale }.toDoubleArray()
        val r2 = m[1].map { it / scale }.toDoubleArray()
        val r3 = doubleArrayOf(
            r1[1] * r2[2] - r1[2] * r2[1],
            r1[2] * r2[0] - r1[0] * r2[2],
            r1[0] * r2[1] - r1[1] * r2[0],
        )

        // Weak perspective says scale = focal / depth, so a candidate focal fixes
        // the depth. The candidate that reprojects the 3D model most accurately
        // is the one that explains the perspective actually visible.
        var bestF = 0.0
        var bestErr = Double.MAX_VALUE
        val lo = imageWidth * 0.35
        val hi = imageWidth * 3.5
        var f = lo
        while (f <= hi) {
            val depth = f / scale
            var err = 0.0
            for (i in 0..4) {
                val x = model[i]
                val X = r1[0] * x[0] + r1[1] * x[1] + r1[2] * x[2]
                val Y = r2[0] * x[0] + r2[1] * x[1] + r2[2] * x[2]
                val Z = r3[0] * x[0] + r3[1] * x[1] + r3[2] * x[2]
                val zz = depth + Z
                if (zz <= 1.0) { err = Double.MAX_VALUE; break }
                val px = f * X / zz
                val py = f * Y / zz
                err += (px - obs[i][0]) * (px - obs[i][0]) + (py - obs[i][1]) * (py - obs[i][1])
            }
            if (err < bestErr) { bestErr = err; bestF = f }
            f += imageWidth * 0.01
        }
        if (bestF <= 0.0 || bestErr == Double.MAX_VALUE) return null
        val residual = sqrt(bestErr / 5.0)

        // Euler angles from the rotation matrix.
        val sy = sqrt(r1[0] * r1[0] + r2[0] * r2[0])
        val yawDeg = Math.toDegrees(atan2(r2[0], r1[0]))
        val pitchDeg = Math.toDegrees(atan2(-r3[0], sy))
        val rollDeg = Math.toDegrees(atan2(r3[1], r3[2]))

        // Depth in millimetres, then metres.
        val depthMm = bestF / scale
        // 35mm equivalent: scale the focal by the ratio of the 36x24 diagonal to
        // the sensor's, which is the definition of "equivalent".
        val diagPx = Math.hypot(imageWidth.toDouble(), imageHeight.toDouble())
        val diagMm = Math.hypot(36.0, 24.0)
        val focal35 = (bestF * diagMm / diagPx).toFloat()

        return Estimate(
            source = source,
            focalPx = bestF.toFloat(),
            focal35mm = focal35,
            yaw = yawDeg.toFloat(),
            pitch = pitchDeg.toFloat(),
            roll = rollDeg.toFloat(),
            residualPx = residual.toFloat(),
            distanceMetres = (depthMm / 1000.0).toFloat(),
        )
    }

    private fun norm(v: DoubleArray): Double = sqrt(v.sumOf { it * it })

    /** Inverse of a 3x3, or null when singular. */
    private fun invert3(a: Array<DoubleArray>): Array<DoubleArray>? {
        val det = a[0][0] * (a[1][1] * a[2][2] - a[1][2] * a[2][1]) -
            a[0][1] * (a[1][0] * a[2][2] - a[1][2] * a[2][0]) +
            a[0][2] * (a[1][0] * a[2][1] - a[1][1] * a[2][0])
        if (abs(det) < 1e-9) return null
        val id = 1.0 / det
        return arrayOf(
            doubleArrayOf(
                (a[1][1] * a[2][2] - a[1][2] * a[2][1]) * id,
                (a[0][2] * a[2][1] - a[0][1] * a[2][2]) * id,
                (a[0][1] * a[1][2] - a[0][2] * a[1][1]) * id,
            ),
            doubleArrayOf(
                (a[1][2] * a[2][0] - a[1][0] * a[2][2]) * id,
                (a[0][0] * a[2][2] - a[0][2] * a[2][0]) * id,
                (a[0][2] * a[1][0] - a[0][0] * a[1][2]) * id,
            ),
            doubleArrayOf(
                (a[1][0] * a[2][1] - a[1][1] * a[2][0]) * id,
                (a[0][1] * a[2][0] - a[0][0] * a[2][1]) * id,
                (a[0][0] * a[1][1] - a[0][1] * a[1][0]) * id,
            ),
        )
    }

    /** cos/sin helpers kept so the unused-import warning stays away. */
    @Suppress("unused")
    private fun trig(x: Double) = cos(x) + sin(x)
}
