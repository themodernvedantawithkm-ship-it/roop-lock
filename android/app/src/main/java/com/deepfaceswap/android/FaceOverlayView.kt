package com.deepfaceswap.android

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View

/**
 * The target image with the faces the detector actually found drawn on it.
 *
 * The app has always known where the faces are - the detector returns a box for
 * each one - but it only ever showed small crop thumbnails, so you were choosing
 * between pictures of faces without seeing where they sat in the photo. On a
 * group shot that is guesswork.
 *
 * Tapping a box selects that face. Selection is the index the engine's mapping
 * already uses, so choosing here is the same choice the manual mapping screen
 * makes, just made where you can see it.
 */
class FaceOverlayView(
    context: Context,
    private val photo: Bitmap,
    private val boxes: List<RectF>,
) : View(context) {

    /** Index of the selected face, or -1 for none. */
    var selected: Int = -1
        set(value) {
            field = value
            invalidate()
        }

    var onPick: ((Int) -> Unit)? = null

    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.5f * resources.displayMetrics.density
        color = Color.parseColor("#4C93FF")
    }
    private val selectedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 4f * resources.displayMetrics.density
        color = Color.parseColor("#2EC9A8")
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 13f * resources.displayMetrics.density
        isFakeBoldText = true
    }
    private val labelBg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#4C93FF")
    }

    private val aspect = if (photo.width > 0) photo.height.toFloat() / photo.width else 1f

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        setMeasuredDimension(w, (w * aspect).toInt().coerceAtLeast(120))
    }

    /** The box scaled from image pixels into view pixels. */
    private fun scaled(box: RectF): RectF {
        val sx = width / photo.width.toFloat()
        val sy = height / photo.height.toFloat()
        return RectF(box.left * sx, box.top * sy, box.right * sx, box.bottom * sy)
    }

    override fun onDraw(canvas: Canvas) {
        if (width == 0 || height == 0) return
        canvas.drawBitmap(photo, null, Rect(0, 0, width, height), bitmapPaint)
        boxes.forEachIndexed { i, box ->
            val r = scaled(box)
            canvas.drawRect(r, if (i == selected) selectedPaint else boxPaint)
            val label = "${i + 1}"
            val pad = 4f * resources.displayMetrics.density
            val tw = labelPaint.measureText(label)
            val th = labelPaint.textSize
            val bg = RectF(r.left, r.top - th - pad * 2, r.left + tw + pad * 2, r.top)
            canvas.drawRect(bg, labelBg)
            canvas.drawText(label, bg.left + pad, bg.bottom - pad, labelPaint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action != MotionEvent.ACTION_UP) return true
        // Smallest box under the finger wins, so a small face inside a large
        // one is still selectable.
        var best = -1
        var bestArea = Float.MAX_VALUE
        boxes.forEachIndexed { i, box ->
            val r = scaled(box)
            if (r.contains(event.x, event.y)) {
                val area = r.width() * r.height()
                if (area < bestArea) {
                    bestArea = area
                    best = i
                }
            }
        }
        if (best >= 0) {
            selected = best
            onPick?.invoke(best)
        }
        return true
    }

    companion object {
        /** Build one, or null when the image cannot be read. */
        fun of(context: Context, file: java.io.File, boxes: List<RectF>): FaceOverlayView? {
            val opts = BitmapFactory.Options().apply { inSampleSize = 2 }
            val bmp = runCatching { BitmapFactory.decodeFile(file.absolutePath, opts) }
                .getOrNull() ?: return null
            return FaceOverlayView(context, bmp, boxes)
        }
    }
}
