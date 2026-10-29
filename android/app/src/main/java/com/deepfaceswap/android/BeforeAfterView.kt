package com.deepfaceswap.android

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.view.MotionEvent
import android.view.View

/**
 * The result against the original, split by a divider you drag.
 *
 * "Looks wrong" is the only signal a swap gives you without a comparison. Two
 * separate images in a list make you hold one in your head; a divider makes the
 * difference immediate, because the same part of the face is on both sides of it.
 *
 * Both bitmaps are scaled to the same width, and the divider position is a
 * fraction rather than a pixel so a rotation or resize does not lose it.
 */
class BeforeAfterView(
    context: Context,
    private val before: Bitmap,
    private val after: Bitmap,
) : View(context) {

    private var split = 0.5f
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val barPaint = Paint().apply {
        color = Color.WHITE
        strokeWidth = 3f * resources.displayMetrics.density
    }
    private val knobPaint = Paint().apply { color = Color.WHITE }

    /** Drawn height, kept so the view can report a sensible height to the layout. */
    private val aspect: Float =
        if (after.width > 0) after.height.toFloat() / after.width else 1f

    init {
        isClickable = true
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = (w * aspect).toInt().coerceAtLeast(120)
        setMeasuredDimension(w, h)
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        // The original fills the frame; the result is clipped to the right of
        // the divider, so the left side is always "what you gave it".
        canvas.drawBitmap(before, null, Rect(0, 0, w.toInt(), h.toInt()), bitmapPaint)
        val cut = (w * split).toInt()
        canvas.save()
        canvas.clipRect(cut, 0, w.toInt(), h.toInt())
        canvas.drawBitmap(after, null, Rect(0, 0, w.toInt(), h.toInt()), bitmapPaint)
        canvas.restore()

        canvas.drawLine(cut.toFloat(), 0f, cut.toFloat(), h, barPaint)
        canvas.drawCircle(cut.toFloat(), h / 2f, 11f * resources.displayMetrics.density, knobPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                split = (event.x / width.coerceAtLeast(1)).coerceIn(0f, 1f)
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    companion object {
        /** Build one from two files, or null when either cannot be read. */
        fun of(context: Context, beforeFile: java.io.File, afterFile: java.io.File): BeforeAfterView? {
            val opts = BitmapFactory.Options().apply { inSampleSize = 2 }
            val a = runCatching { BitmapFactory.decodeFile(beforeFile.absolutePath, opts) }
                .getOrNull() ?: return null
            val b = runCatching { BitmapFactory.decodeFile(afterFile.absolutePath, opts) }
                .getOrNull() ?: return null
            return BeforeAfterView(context, a, b)
        }
    }
}
