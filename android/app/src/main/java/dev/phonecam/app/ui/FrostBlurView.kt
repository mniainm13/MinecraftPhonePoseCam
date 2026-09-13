package dev.phonecam.app.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.util.AttributeSet
import android.widget.FrameLayout

/**
 * Frosted container: draws full-frame blur bitmap cropped to this view, then children.
 * Caller owns bitmap recycle (single shared frame).
 */
class FrostBlurView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : FrameLayout(context, attrs) {

    private var blur: Bitmap? = null
    private var frameW = 1
    private var frameH = 1
    private var leftInFrame = 0f
    private var topInFrame = 0f
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val tintPaint = Paint()
    private val clip = Path()
    private val src = Rect()
    private val dst = RectF()
    private var cornerPx = 20f * resources.displayMetrics.density
    private var fallback = 0x4D000000 // translucent when blur off

    init {
        setWillNotDraw(false)
        tintPaint.color = 0x40000000
    }

    fun setCornerDp(dp: Float) {
        cornerPx = dp * resources.displayMetrics.density
        invalidate()
    }

    fun setTint(color: Int) {
        tintPaint.color = color
        invalidate()
    }

    fun setFallbackColor(color: Int) {
        fallback = color
        invalidate()
    }

    fun setBlur(bm: Bitmap?, fw: Int, fh: Int, left: Float, top: Float) {
        blur = bm
        frameW = maxOf(1, fw)
        frameH = maxOf(1, fh)
        leftInFrame = left
        topInFrame = top
        postInvalidate()
    }

    override fun dispatchDraw(canvas: Canvas) {
        val w = width
        val h = height
        if (w > 0 && h > 0) {
            dst.set(0f, 0f, w.toFloat(), h.toFloat())
            clip.reset()
            clip.addRoundRect(dst, cornerPx, cornerPx, Path.Direction.CW)
            canvas.save()
            canvas.clipPath(clip)
            val b = blur
            if (b != null && !b.isRecycled) {
                val bw = b.width
                val bh = b.height
                src.set(
                    (leftInFrame / frameW * bw).toInt(),
                    (topInFrame / frameH * bh).toInt(),
                    ((leftInFrame + w) / frameW * bw).toInt(),
                    ((topInFrame + h) / frameH * bh).toInt(),
                )
                src.intersect(0, 0, bw, bh)
                canvas.drawBitmap(b, src, dst, paint)
                canvas.drawRect(dst, tintPaint)
            } else {
                canvas.drawColor(fallback)
            }
            canvas.restore()
        }
        super.dispatchDraw(canvas)
    }
}
