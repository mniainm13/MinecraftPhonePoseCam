package dev.phonecam.app.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min

/**
 * Zoom capsule. Uniform pad on all four sides; width hugs preset count.
 * Drag = continuous; live 1-decimal value on nearest preset.
 */
class ZoomPillBar @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0,
) : View(context, attrs, defStyle) {

    var presets: List<Float> = listOf(0.6f, 1f, 2f)
        set(value) {
            field = value.distinct().sorted()
            requestLayout()
            invalidate()
        }

    var zoomMin = 0.25f
    var zoomMax = 4f

    var zoom = 1f
        private set

    var onZoom: ((Float) -> Unit)? = null

    private val density = resources.displayMetrics.density
    private val pillH = 44f * density
    /** Same value for top/bottom AND left/right — tight so disc looks large. */
    private val pad = 3.5f * density
    private val itemGap = 8f * density

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x1A000000.toInt() }
    private val activeBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x99000000.toInt() }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xE6FFFFFF.toInt()
        textAlign = Paint.Align.CENTER
        textSize = 12f * density
    }
    private val activeTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFF3B30.toInt()
        textAlign = Paint.Align.CENTER
        textSize = 13f * density
        isFakeBoldText = true
    }

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var dragging = false
    private var maybeTap = false

    private val discD: Float get() = pillH - 2f * pad
    private val discR: Float get() = discD / 2f

    /** Per-item text widths and center X (relative to content left = 0). */
    private var textWs = FloatArray(0)
    private var centers = FloatArray(0)
    private var contentW = 0f

    fun setZoom(z: Float, notify: Boolean = true) {
        val zz = z.coerceIn(zoomMin, zoomMax)
        if (abs(zz - zoom) < 1e-4f) {
            invalidate()
            return
        }
        zoom = zz
        invalidate()
        if (notify) onZoom?.invoke(zoom)
    }

    fun animateTo(target: Float) {
        val t = target.coerceIn(zoomMin, zoomMax)
        val start = zoom
        val t0 = System.nanoTime()
        val dur = 200_000_000L
        post(object : Runnable {
            override fun run() {
                val k = min(1f, (System.nanoTime() - t0).toFloat() / dur)
                val e = 1f - (1f - k) * (1f - k)
                setZoom(start + (t - start) * e, notify = true)
                if (k < 1f) postOnAnimation(this)
            }
        })
    }

    private fun labelOf(p: Float): String {
        return if (abs(p - p.toInt()) < 0.05f) "${p.toInt()}×" else "%.1f×".format(p)
    }

    private fun formatLive(z: Float): String {
        return if (abs(z - z.toInt()) < 0.05f) "${z.toInt()}×" else "%.1f×".format(z)
    }

    /**
     * Lay out items: each occupies max(textW, discD), centers spaced by itemGap.
     * End padding == vertical pad so left/right clearance matches top/bottom.
     */
    private fun relayout(gapScale: Float = 1f) {
        val n = presets.size
        if (n == 0) {
            textWs = FloatArray(0)
            centers = FloatArray(0)
            contentW = discD + pad * 2
            return
        }
        textWs = FloatArray(n)
        centers = FloatArray(n)
        val g = itemGap * gapScale
        var x = 0f
        for (i in 0 until n) {
            val live = labelOf(presets[i])
            val tw = max(textPaint.measureText(live), activeTextPaint.measureText(live))
            textWs[i] = tw
            // occupied width: at least the disc, at least the text
            val occ = max(tw, discD)
            centers[i] = x + occ / 2f
            x += occ + if (i < n - 1) g else 0f
        }
        contentW = x
    }

    private fun pillLeft(): Float = (width - contentW - pad * 2).coerceAtLeast(0f) / 2f + pad

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val maxW = MeasureSpec.getSize(widthMeasureSpec).toFloat()
        val mode = MeasureSpec.getMode(widthMeasureSpec)
        relayout(1f)
        var want = contentW + pad * 2
        if (mode != MeasureSpec.UNSPECIFIED && maxW > 0 && want > maxW) {
            // compress gaps then rely on occ>=text still
            relayout(0.4f)
            want = contentW + pad * 2
            if (want > maxW) {
                // last resort: scale content
                val scale = ((maxW - pad * 2) / contentW).coerceIn(0.55f, 1f)
                for (i in centers.indices) centers[i] *= scale
                contentW *= scale
                want = contentW + pad * 2
            }
        }
        val w = when (mode) {
            MeasureSpec.EXACTLY -> maxW.toInt()
            MeasureSpec.AT_MOST -> min(want, maxW).toInt()
            else -> want.toInt()
        }
        setMeasuredDimension(max(w, suggestedMinimumWidth), resolveSize(pillH.toInt(), heightMeasureSpec))
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w > 0 && presets.isNotEmpty()) {
            relayout(1f)
            if (contentW + pad * 2 > w) {
                relayout(0.4f)
                if (contentW + pad * 2 > w) {
                    val scale = ((w - pad * 2) / contentW).coerceIn(0.55f, 1f)
                    for (i in centers.indices) centers[i] *= scale
                    contentW *= scale
                }
            }
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val h = pillH
        val w = width.toFloat()
        if (presets.isEmpty()) {
            canvas.drawRoundRect(RectF(0f, 0f, w, h), h / 2f, h / 2f, bgPaint)
            return
        }

        val left = pillLeft()
        // Background exactly wraps content with uniform pad
        val bg = RectF(left - pad, 0f, left + contentW + pad, h)
        canvas.drawRoundRect(bg, h / 2f, h / 2f, bgPaint)

        val nearest = nearestIndex(zoom)
        for (i in presets.indices) {
            val cx = left + centers[i]
            if (i == nearest) {
                canvas.drawCircle(cx, h / 2f, discR, activeBgPaint)
            }
            val label = if (i == nearest) formatLive(zoom) else labelOf(presets[i])
            val tp = if (i == nearest) activeTextPaint else textPaint
            val ty = h / 2f - (tp.descent() + tp.ascent()) / 2f
            canvas.drawText(label, cx, ty, tp)
        }
    }

    private fun nearestIndex(z: Float): Int {
        var best = 0
        var bd = Float.MAX_VALUE
        presets.forEachIndexed { i, p ->
            val d = abs(ln(p.coerceAtLeast(1e-3f)) - ln(z.coerceAtLeast(1e-3f)))
            if (d < bd) {
                bd = d
                best = i
            }
        }
        return best
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x; downY = event.y; lastX = event.x
                dragging = false; maybeTap = true
                parent?.requestDisallowInterceptTouchEvent(true)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.x - downX
                val dy = event.y - downY
                if (!dragging && (abs(dx) > touchSlop || abs(dy) > touchSlop)) {
                    if (abs(dx) > abs(dy)) {
                        dragging = true; maybeTap = false
                    } else {
                        maybeTap = false
                        return false
                    }
                }
                if (dragging) {
                    val step = event.x - lastX
                    lastX = event.x
                    val factor = exp(step * 0.0045f)
                    setZoom(exp(ln(zoom.coerceAtLeast(1e-3f)) + ln(factor)), notify = true)
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                if (maybeTap && !dragging) {
                    val i = indexAt(event.x)
                    if (i >= 0) animateTo(presets[i])
                }
                dragging = false; maybeTap = false
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun indexAt(x: Float): Int {
        if (presets.isEmpty()) return -1
        val left = pillLeft()
        var best = 0
        var bd = Float.MAX_VALUE
        centers.indices.forEach { i ->
            val d = abs(x - (left + centers[i]))
            if (d < bd) {
                bd = d; best = i
            }
        }
        return best
    }
}
