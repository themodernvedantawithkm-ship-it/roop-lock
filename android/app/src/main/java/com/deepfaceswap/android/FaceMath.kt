package com.deepfaceswap.android

import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.sin

/**
 * Facial proportions, pose and the maths that compares two faces.
 *
 * Measurement only. Nothing here changes a swap - it reports what the two images
 * actually are, so a bad result can be explained rather than guessed at.
 *
 * Everything is expressed as a ratio against the interocular distance, which is
 * the standard way to make these numbers independent of how big the face is or
 * how far away the camera was. Ratios are what people read as "the same face";
 * absolute pixel distances are not.
 */
object FaceMath {

    /** The five keypoints, in the order the detector returns them. */
    data class Landmarks(val points: List<Pair<Float, Float>>) {
        val leftEye get() = points[0]
        val rightEye get() = points[1]
        val nose get() = points[2]
        val leftMouth get() = points[3]
        val rightMouth get() = points[4]
    }

    /** A face as the geometry call reports it. */
    data class Face(
        val box: android.graphics.RectF,
        val score: Float,
        val landmarks: Landmarks,
    )

    data class Geometry(val width: Int, val height: Int, val faces: List<Face>)

    /** Head orientation in degrees. Approximate: derived from five points only. */
    data class Pose(val yaw: Float, val pitch: Float, val roll: Float) {
        /** The single number for "how far from front-on is this". */
        val offAxis: Float get() = maxOf(abs(yaw), abs(pitch))
    }

    /** The measurements that describe a face's proportions. All dimensionless. */
    data class Proportions(
        val eyeSpacingOverFaceWidth: Float,
        val eyeToNoseOverIod: Float,
        val noseToMouthOverIod: Float,
        val mouthWidthOverIod: Float,
        val faceHeightOverIod: Float,
        val faceAspect: Float,
    ) {
        fun asList(): List<Float> = listOf(
            eyeSpacingOverFaceWidth, eyeToNoseOverIod, noseToMouthOverIod,
            mouthWidthOverIod, faceHeightOverIod, faceAspect,
        )

        fun labels(): List<String> = listOf(
            "eye spacing / face width",
            "eye to nose / IOD",
            "nose to mouth / IOD",
            "mouth width / IOD",
            "face height / IOD",
            "face height / width",
        )
    }

    fun iod(l: Landmarks): Float =
        hypot(l.rightEye.first - l.leftEye.first, l.rightEye.second - l.leftEye.second)

    fun pose(l: Landmarks): Pose {
        val eyeDx = l.rightEye.first - l.leftEye.first
        val eyeDy = l.rightEye.second - l.leftEye.second
        val roll = Math.toDegrees(atan2(eyeDy, eyeDx).toDouble()).toFloat()

        // Yaw from how unevenly the nose sits between the two eyes.
        //
        // The first version of this divided the nose's *vertical* offset by half
        // the eye distance and called it yaw, which reports about 66 degrees for
        // any normal front-on face - the number was really the nose drop, i.e.
        // pitch, under the wrong name. Distance to each eye is symmetric for a
        // front-on face and collapses on one side in profile, which is what yaw
        // actually looks like in two dimensions.
        val dLeft = hypot(l.nose.first - l.leftEye.first, l.nose.second - l.leftEye.second)
        val dRight = hypot(l.nose.first - l.rightEye.first, l.nose.second - l.rightEye.second)
        val spread = (dLeft + dRight).coerceAtLeast(1f)
        val yawRatio = ((dRight - dLeft) / spread).coerceIn(-1f, 1f)
        val yaw = Math.toDegrees(asin(yawRatio.toDouble())).toFloat()

        // Pitch from where the nose sits vertically between the eyes and the
        // mouth, measured after undoing roll so a tilted head is not read as a
        // nodding one. Front-on lands near 0.575 of the way down.
        val eyeMidX = (l.leftEye.first + l.rightEye.first) / 2f
        val eyeMidY = (l.leftEye.second + l.rightEye.second) / 2f
        val rad = Math.toRadians(-roll.toDouble())
        val c = cos(rad).toFloat()
        val s = sin(rad).toFloat()
        fun yRot(x: Float, y: Float): Float =
            (x - eyeMidX) * s + (y - eyeMidY) * c

        val mouthDrop = yRot(
            (l.leftMouth.first + l.rightMouth.first) / 2f,
            (l.leftMouth.second + l.rightMouth.second) / 2f,
        )
        val noseDrop = yRot(l.nose.first, l.nose.second)
        val pitchRatio = if (mouthDrop > 1f) (noseDrop / mouthDrop).coerceIn(-1f, 2f) else 0.575f
        val pitch = (pitchRatio - 0.575f) * 90f

        return Pose(yaw = yaw, pitch = pitch, roll = roll)
    }

    fun proportions(face: Face): Proportions {
        val l = face.landmarks
        val d = iod(l).coerceAtLeast(1f)
        val faceW = face.box.width().coerceAtLeast(1f)
        val faceH = face.box.height().coerceAtLeast(1f)
        val eyeMidY = (l.leftEye.second + l.rightEye.second) / 2f
        val mouthMidY = (l.leftMouth.second + l.rightMouth.second) / 2f
        val mouthW = abs(l.rightMouth.first - l.leftMouth.first).coerceAtLeast(1f)

        return Proportions(
            eyeSpacingOverFaceWidth = d / faceW,
            eyeToNoseOverIod = abs(l.nose.second - eyeMidY) / d,
            noseToMouthOverIod = abs(mouthMidY - l.nose.second) / d,
            mouthWidthOverIod = mouthW / d,
            faceHeightOverIod = faceH / d,
            faceAspect = faceH / faceW,
        )
    }

    /**
     * How alike two faces are in proportion, 0..1.
     *
     * Each measurement is compared as a log ratio, so a 20% difference counts the
     * same whether it is larger or smaller, then averaged:
     *
     *   shape = exp(-mean |log(r_source / r_target)|)
     *
     * 1.0 means identical proportions. There is no texture or colour in this -
     * it is purely the arrangement, which is the point.
     */
    fun shapeScore(a: Proportions, b: Proportions): Float {
        val ra = a.asList()
        val rb = b.asList()
        if (ra.size != rb.size || ra.isEmpty()) return 0f
        var sum = 0f
        var count = 0
        ra.indices.forEach { i ->
            if (ra[i] > 0f && rb[i] > 0f) {
                sum += abs(ln(ra[i] / rb[i]))
                count++
            }
        }
        if (count == 0) return 0f
        return exp(-(sum / count)).coerceIn(0f, 1f)
    }

    /** Per-measurement difference, as a percentage the source is over the target. */
    fun differences(a: Proportions, b: Proportions): List<Float> {
        val ra = a.asList()
        val rb = b.asList()
        return ra.indices.map { i ->
            if (ra[i] > 0f && rb[i] > 0f) ((ra[i] / rb[i]) - 1f) * 100f else 0f
        }
    }

    /**
     * How far radial distortion moves the outer face landmarks, in pixels.
     *
     * Brown-Conrady, to first order: a point at radius r is displaced by roughly
     * k1 * r^3. Reported so the size of the correction is visible rather than
     * assumed.
     */
    fun distortionShiftPx(face: Face, image: Geometry, k1: Float): Float {
        if (k1 <= 0f || image.width <= 0) return 0f
        val cx = image.width / 2f
        val cy = image.height / 2f
        val halfDiag = hypot(cx, cy).coerceAtLeast(1f)
        var worst = 0f
        face.landmarks.points.forEach { (x, y) ->
            val dx = x - cx
            val dy = y - cy
            val r = hypot(dx, dy) / halfDiag
            worst = maxOf(worst, k1 * r * r * r * halfDiag)
        }
        return worst
    }

    /**
     * Verdict on whether the two faces can be reconciled.
     *
     * Deliberately conservative: it refuses to call a large mismatch "fine" just
     * because nothing has been tried yet.
     */
    fun verdict(shape: Float, poseDelta: Float): String = when {
        poseDelta > 25f -> "Faces are at very different angles - identity transfer will be weak"
        shape >= 0.90f -> "Proportions already agree closely"
        shape >= 0.80f -> "Proportions are close enough to leave alone"
        shape >= 0.70f -> "Proportions differ visibly - a shape adjustment could help"
        else -> "Proportions differ a lot - expect the swap to read as a different face shape"
    }
}
