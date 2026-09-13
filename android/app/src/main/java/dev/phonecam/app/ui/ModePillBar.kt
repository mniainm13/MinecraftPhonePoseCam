package dev.phonecam.app.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.max
import kotlin.math.min

/**
 * Mode capsule (转向 / 自由). Selection capsule uses the same dark fill as ZoomPillBar.
 */
class ModePillBar @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0,
) : View(context, attrs, defStyle) {

    var items: List<String> = listOf("转向", "自由")
        set(value) {
            field = value
            requestLayout()
            invalidate()
        }

    var selected = 0
        set(value) {
            field = value.coerceIn(0, (items.size - 1).coerceAtLeast(0))
            invalidate()
        }

    var onSelect: ((Int) -> Unit)? = null

    private val density = resources.displayMetrics.density
    private val pillH = 40f * density
    /** Same pad all four sides — tight. */
    private val vInset = 3.5f * density
    private val textPadX = 12f * density

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x1A000000.toInt() }
    /** Match ZoomPillBar active disc (dark), not blue. */
    private val activeBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x99000000.toInt() }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xE6FFFFFF.toInt()
        textAlign = Paint.Align.CENTER
        textSize = 13f * density
    }
    private val activeTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt()
        textAlign = Paint.Align.CENTER
        textSize = 13f * density
        isFakeBoldText = true
    }

    private fun labelW(i: Int): Float {
        val t = items.getOrNull(i) ?: return 0f
        return max(textPaint.measureText(t), activeTextPaint.measureText(t)) + textPadX * 2
    }

    private fun contentWidth(): Float {
        var sum = 0f
        items.indices.forEach { sum += labelW(it) }
        val gap = 6f * density
        return sum + gap * (items.size - 1).coerceAtLeast(0)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        // Width hugs labels — background is exactly this width
        val w = (contentWidth() + vInset * 2).toInt()
        val mode = MeasureSpec.getMode(widthMeasureSpec)
        val size = MeasureSpec.getSize(widthMeasureSpec)
        val finalW = when (mode) {
            MeasureSpec.EXACTLY -> size
            MeasureSpec.AT_MOST -> min(w, size)
            else -> w
        }
        setMeasuredDimension(
            max(finalW, suggestedMinimumWidth),
            resolveSize(pillH.toInt(), heightMeasureSpec)
        )
    }

    override fun onDraw(canvas: Canvas) {
        val h = height.toFloat()
        val w = width.toFloat()
        canvas.drawRoundRect(RectF(0f, 0f, w, h), h / 2f, h / 2f, bgPaint)
        if (items.isEmpty()) return

        val gap = 6f * density
        var x = vInset
        // center the group if extra width
        val extra = w - vInset * 2 - contentWidth()
        if (extra > 0f) x += extra / 2f

        items.forEachIndexed { i, label ->
            val lw = labelW(i)
            val cy = h / 2f
            if (i == selected) {
                // capsule height matches pill inset; width follows text
                val top = vInset
                val bottom = h - vInset
                val r = (bottom - top) / 2f
                canvas.drawRoundRect(RectF(x, top, x + lw, bottom), r, r, activeBgPaint)
            }
            val tp = if (i == selected) activeTextPaint else textPaint
            val ty = cy - (tp.descent() + tp.ascent()) / 2f
            canvas.drawText(label, x + lw / 2f, ty, tp)
            x += lw + gap
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_UP) {
            val i = indexAt(event.x)
            if (i >= 0) {
                selected = i
                onSelect?.invoke(i)
                invalidate()
            }
        }
        return true
    }

    private fun indexAt(x: Float): Int {
        if (items.isEmpty()) return -1
        val gap = 6f * density
        var left = vInset
        val extra = width - vInset * 2 - contentWidth()
        if (extra > 0f) left += extra / 2f
        items.indices.forEach { i ->
            val lw = labelW(i)
            if (x >= left && x <= left + lw) return i
            left += lw + gap
        }
        return -1
    }
}
