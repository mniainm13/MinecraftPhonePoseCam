package dev.phonecam.app.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Draggable crop rect over a dark scrim.
 * Exposes normalized crop (0-1) + optional fixed aspect.
 */
class CropOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    /** Crop in view-normalized 0-1. */
    var crop = RectF(0.15f, 0.15f, 0.85f, 0.85f)
        private set

    /** null = free; else width/height ratio lock. */
    var aspect: Float? = null

    var onCropChanged: ((RectF) -> Unit)? = null

    private val density = resources.displayMetrics.density
    private val touchSlop = (28f * density).toInt()

    private val scrimPaint = Paint().apply { color = 0x99000000.toInt() }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt()
        style = Paint.Style.STROKE
        strokeWidth = 2.5f * density
    }
    private val cornerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt()
        style = Paint.Style.STROKE
        strokeWidth = 4f * density
    }
    private val fillPaint = Paint().apply { color = 0x33FFFFFF }
    private val path = Path()

    private var dragMode = 0 // 0 none, 1 move, 2 tl, 3 tr, 4 bl, 5 br
    private var lastX = 0f
    private var lastY = 0f

    fun setCropNorm(l: Float, t: Float, r: Float, b: Float) {
        crop.set(
            l.coerceIn(0f, 1f),
            t.coerceIn(0f, 1f),
            r.coerceIn(0f, 1f).coerceAtLeast(l + 0.05f),
            b.coerceIn(0f, 1f).coerceAtLeast(t + 0.05f),
        )
        applyAspectFromCenter()
        invalidate()
        onCropChanged?.invoke(RectF(crop))
    }

    fun resetFull() {
        crop.set(0f, 0f, 1f, 1f)
        invalidate()
        onCropChanged?.invoke(RectF(crop))
    }

    fun applyAspectFromCenter() {
        val ar = aspect ?: return
        val w = crop.width()
        val h = crop.height()
        if (w <= 0f || h <= 0f) return
        val cx = crop.centerX()
        val cy = crop.centerY()
        var nw = w
        var nh = h
        if (w / h > ar) nw = h * ar else nh = w / ar
        var l = cx - nw / 2f
        var t = cy - nh / 2f
        var r = cx + nw / 2f
        var b = cy + nh / 2f
        if (l < 0f) { r -= l; l = 0f }
        if (t < 0f) { b -= t; t = 0f }
        if (r > 1f) { l -= r - 1f; r = 1f }
        if (b > 1f) { t -= b - 1f; b = 1f }
        crop.set(max(0f, l), max(0f, t), min(1f, r), min(1f, b))
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val rect = RectF(crop.left * w, crop.top * h, crop.right * w, crop.bottom * h)

        // scrim outside crop
        path.reset()
        path.addRect(RectF(0f, 0f, w, h), Path.Direction.CW)
        path.addRect(rect, Path.Direction.CCW)
        canvas.drawPath(path, scrimPaint)

        canvas.drawRect(rect, borderPaint)
        val cl = 18f * density
        // corner handles
        canvas.drawLine(rect.left, rect.top, rect.left + cl, rect.top, cornerPaint)
        canvas.drawLine(rect.left, rect.top, rect.left, rect.top + cl, cornerPaint)
        canvas.drawLine(rect.right - cl, rect.top, rect.right, rect.top, cornerPaint)
        canvas.drawLine(rect.right, rect.top, rect.right, rect.top + cl, cornerPaint)
        canvas.drawLine(rect.left, rect.bottom - cl, rect.left, rect.bottom, cornerPaint)
        canvas.drawLine(rect.left, rect.bottom, rect.left + cl, rect.bottom, cornerPaint)
        canvas.drawLine(rect.right - cl, rect.bottom, rect.right, rect.bottom, cornerPaint)
        canvas.drawLine(rect.right, rect.bottom - cl, rect.right, rect.bottom, cornerPaint)
        // thirds
        val p = Paint(borderPaint).apply { strokeWidth = 1f * density; color = 0x66FFFFFF }
        canvas.drawLine(rect.left + rect.width() / 3, rect.top, rect.left + rect.width() / 3, rect.bottom, p)
        canvas.drawLine(rect.left + rect.width() * 2 / 3, rect.top, rect.left + rect.width() * 2 / 3, rect.bottom, p)
        canvas.drawLine(rect.left, rect.top + rect.height() / 3, rect.right, rect.top + rect.height() / 3, p)
        canvas.drawLine(rect.left, rect.top + rect.height() * 2 / 3, rect.right, rect.top + rect.height() * 2 / 3, p)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val w = width.toFloat()
        val h = height.toFloat()
        val x = event.x / w
        val y = event.y / h
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                dragMode = hitMode(event.x, event.y, w, h)
                lastX = x
                lastY = y
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = x - lastX
                val dy = y - lastY
                lastX = x
                lastY = y
                when (dragMode) {
                    1 -> {
                        val cw = crop.width()
                        val ch = crop.height()
                        var l = (crop.left + dx).coerceIn(0f, 1f - cw)
                        var t = (crop.top + dy).coerceIn(0f, 1f - ch)
                        crop.set(l, t, l + cw, t + ch)
                    }
                    2 -> moveCorner(x, y, left = true, top = true)
                    3 -> moveCorner(x, y, left = false, top = true)
                    4 -> moveCorner(x, y, left = true, top = false)
                    5 -> moveCorner(x, y, left = false, top = false)
                }
                if (dragMode != 0) {
                    invalidate()
                    onCropChanged?.invoke(RectF(crop))
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                dragMode = 0
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun moveCorner(nx: Float, ny: Float, left: Boolean, top: Boolean) {
        var l = crop.left
        var t = crop.top
        var r = crop.right
        var b = crop.bottom
        if (left) l = nx.coerceIn(0f, r - 0.05f) else r = nx.coerceIn(l + 0.05f, 1f)
        if (top) t = ny.coerceIn(0f, b - 0.05f) else b = ny.coerceIn(t + 0.05f, 1f)
        val ar = aspect
        if (ar != null) {
            val cw = r - l
            val ch = b - t
            if (cw / ch > ar) {
                val nh = cw / ar
                if (top) t = b - nh else b = t + nh
                if (t < 0f) { b -= t; t = 0f }
                if (b > 1f) { t -= b - 1f; b = 1f }
            } else {
                val nw = ch * ar
                if (left) l = r - nw else r = l + nw
                if (l < 0f) { r -= l; l = 0f }
                if (r > 1f) { l -= r - 1f; r = 1f }
            }
        }
        crop.set(l.coerceIn(0f, 1f), t.coerceIn(0f, 1f), r.coerceIn(0f, 1f), b.coerceIn(0f, 1f))
    }

    private fun hitMode(px: Float, py: Float, w: Float, h: Float): Int {
        val l = crop.left * w
        val t = crop.top * h
        val r = crop.right * w
        val b = crop.bottom * h
        val s = touchSlop.toFloat()
        fun nearCorner(cx: Float, cy: Float) = abs(px - cx) <= s && abs(py - cy) <= s
        if (nearCorner(l, t)) return 2
        if (nearCorner(r, t)) return 3
        if (nearCorner(l, b)) return 4
        if (nearCorner(r, b)) return 5
        if (px in l..r && py in t..b) return 1
        return 0
    }
}
