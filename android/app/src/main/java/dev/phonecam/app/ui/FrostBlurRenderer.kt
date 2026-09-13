package dev.phonecam.app.ui

import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.util.Log
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * Owns decode SurfaceTexture (OES) + Kawase blur side-path.
 * Main path: OES → screen every frame (same source, no second decode).
 */
class FrostBlurRenderer : GLSurfaceView.Renderer {

    companion object {
        private const val TAG = "FrostBlur"
    }

    fun interface SurfaceReady {
        fun onDecodeSurface(surface: Surface)
    }

    fun interface BlurSink {
        fun onBlur(bm: Bitmap, frameW: Int, frameH: Int)
    }

    private val kf = KawaseFilter()
    private var surfaceReady: SurfaceReady? = null
    private var blurSink: BlurSink? = null

    @Volatile var level = 2 // 0 off, 1 weak, 2 strong
    @Volatile var intervalMs = 50L // 20fps default; 10–60 supported

    private var oesTex = 0
    private var surfaceTexture: SurfaceTexture? = null
    private var decodeSurface: Surface? = null
    private var viewW = 1
    private var viewH = 1

    private val texMatrix = FloatArray(16)
    private val crop = floatArrayOf(0f, 0f, 1f, 1f)
    @Volatile var videoW = 0
    @Volatile var videoH = 0
    /** Degrees, clockwise on screen; converted to radians in shader. */
    @Volatile var rotationDeg = 0f

    /**
     * Crop mode:
     * 0 = 竖屏 — crop desktop to phone/frame AR (tall strip, current)
     * 1 = 横屏 — crop a landscape window from desktop (default 16:9)
     */
    @Volatile var cropMode = 0
    /** 横屏源旋转 180° 翻转：true = -90°，false = +90° */
    @Volatile var landscapeFlip = false

    /**
     * Display frame AR (width/height). 0 = fullscreen fill (no letterbox).
     * 1=1:1, 0.75=3:4, 1.777=16:9, 2.39=遮幅电影
     */
    @Volatile var frameAr = 0f

    fun setVideoSize(w: Int, h: Int) {
        if (w > 0 && h > 0 && (w != videoW || h != videoH)) {
            videoW = w
            videoH = h
        }
    }

    private fun updateCrop() {
        val vw = videoW
        val vh = videoH
        if (vw <= 0 || vh <= 0) {
            crop[0] = 0f; crop[1] = 0f; crop[2] = 1f; crop[3] = 1f
            return
        }
        val landscape = cropMode == 1
        // 横屏：桌面源先转 90°，有效宽高比 = vh/vw
        val srcVideoAr = if (landscape) vh.toFloat() / vw else vw.toFloat() / vh
        val viewAr = if (viewW > 0 && viewH > 0) viewW.toFloat() / viewH else srcVideoAr
        val srcAr = if (frameAr > 0f) frameAr else viewAr
        var u0 = 0f; var v0 = 0f; var u1 = 1f; var v1 = 1f
        if (srcVideoAr > srcAr) {
            val w = srcAr / srcVideoAr
            u0 = 0.5f - 0.5f * w
            u1 = 0.5f + 0.5f * w
        } else {
            val h = srcVideoAr / srcAr
            v0 = 0.5f - 0.5f * h
            v1 = 0.5f + 0.5f * h
        }
        crop[0] = u0; crop[1] = v0; crop[2] = u1; crop[3] = v1
    }

    private val rotRad: Float
        get() = Math.toRadians(rotationDeg.toDouble()).toFloat()
    private val srcRot90: Float
        get() = when {
            cropMode != 1 -> 0f
            landscapeFlip -> -1f
            else -> 1f
        }

    /** Dest viewport in surface pixels: letterbox frame AR, or full. */
    private fun frameViewport(): IntArray {
        val w = viewW
        val h = viewH
        if (frameAr <= 0f || w <= 0 || h <= 0) return intArrayOf(0, 0, w, h)
        val viewAr = w.toFloat() / h
        val dw: Int
        val dh: Int
        if (frameAr > viewAr) {
            dw = w
            dh = (w / frameAr).toInt()
        } else {
            dh = h
            dw = (h * frameAr).toInt()
        }
        val x = (w - dw) / 2
        val y = (h - dh) / 2
        return intArrayOf(x, y, dw, dh)
    }
    private val fbo = IntArray(4)
    private val tex = IntArray(4)
    private val fw = IntArray(4)
    private val fh = IntArray(4)

    private var lastBlurMs = 0L
    private var avgMs = -1.0
    private var blurSeq = 0
    private var overCount = 0

    fun setSurfaceReady(l: SurfaceReady?) { surfaceReady = l }
    fun setBlurSink(l: BlurSink?) { blurSink = l }

    fun releaseDecode() {
        decodeSurface?.release()
        decodeSurface = null
        surfaceTexture?.release()
        surfaceTexture = null
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        kf.init()
        val t = IntArray(1)
        GLES20.glGenTextures(1, t, 0)
        oesTex = t[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTex)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

        surfaceTexture?.release()
        val st = SurfaceTexture(oesTex)
        st.setOnFrameAvailableListener {
            // GLSurfaceView requestRender is called from activity via texture listener
        }
        surfaceTexture = st
        decodeSurface?.release()
        decodeSurface = Surface(st)
        avgMs = -1.0
        blurSeq = 0
        overCount = 0
        lastBlurMs = 0
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        surfaceReady?.onDecodeSurface(decodeSurface!!)
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        viewW = width
        viewH = height
        buildChain(width, height)
    }

    override fun onDrawFrame(gl: GL10?) {
        val st = surfaceTexture ?: return
        st.updateTexImage()
        st.getTransformMatrix(texMatrix)
        updateCrop()

        // Main video path — letterbox into frame AR if set
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        GLES20.glViewport(0, 0, viewW, viewH)
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        val vp = frameViewport()
        GLES20.glViewport(vp[0], vp[1], vp[2], vp[3])
        kf.blitOes(oesTex, texMatrix, crop, rotRad, srcRot90)

        val sink = blurSink
        val now = System.currentTimeMillis()
        if (sink != null && level > 0 && now - lastBlurMs >= intervalMs) {
            lastBlurMs = now
            val t0 = System.nanoTime()
            runBlurChain()
            val bm = readOut() ?: return
            val ms = (System.nanoTime() - t0) / 1e6
            avgMs = if (avgMs < 0) ms else avgMs * 0.85 + ms * 0.15
            blurSeq++
            Log.d(TAG, "blur seq=$blurSeq avg=${"%.1f".format(avgMs)}ms radius=$level out=${fw[3]}x${fh[3]}")
            sink.onBlur(bm, viewW, viewH)
            if (avgMs > 8 && level > 1 && blurSeq > 30) {
                if (++overCount >= 3) {
                    level = 1
                    overCount = 0
                    Log.d(TAG, "blur auto-degrade to radius=1")
                }
            } else overCount = 0
        }
    }

    private fun runBlurChain() {
        val k = if (level == 2) 1f else 0.5f
        // D0: OES → half res (same crop+matrix as main path)
        bindTarget(0)
        kf.blitOes(oesTex, texMatrix, crop, rotRad, srcRot90)
        bindTarget(1)
        kf.blitTex2d(tex[0], 2f * k / fw[1], 2f * k / fh[1])
        bindTarget(2)
        kf.blitTex2d(tex[1], 1f * k / fw[2], 1f * k / fh[2])
        bindTarget(3)
        kf.blitTex2d(tex[2], 1f * k / fw[3], 1f * k / fh[3])
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
    }

    private fun bindTarget(i: Int) {
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo[i])
        GLES20.glViewport(0, 0, fw[i], fh[i])
    }

    private fun readOut(): Bitmap? {
        val w = fw[3]
        val h = fh[3]
        if (w <= 0 || h <= 0) return null
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo[3])
        val buf = ByteBuffer.allocateDirect(w * h * 4).order(ByteOrder.nativeOrder())
        GLES20.glReadPixels(0, 0, w, h, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buf)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        val px = IntArray(w * h)
        buf.asIntBuffer().get(px)
        val out = IntArray(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val p = px[y * w + x]
                val r = p and 0xFF
                val g = (p shr 8) and 0xFF
                val b = (p shr 16) and 0xFF
                val a = p ushr 24
                out[(h - 1 - y) * w + x] = (a shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
        return Bitmap.createBitmap(out, w, h, Bitmap.Config.ARGB_8888)
    }

    private fun buildChain(w: Int, h: Int) {
        val divs = intArrayOf(2, 4, 2, 4)
        val fb = IntArray(4)
        val tx = IntArray(4)
        GLES20.glGenFramebuffers(4, fb, 0)
        GLES20.glGenTextures(4, tx, 0)
        for (i in 0 until 4) {
            if (fbo[i] != 0) GLES20.glDeleteFramebuffers(1, fbo, i)
            if (tex[i] != 0) GLES20.glDeleteTextures(1, tex, i)
            fbo[i] = fb[i]
            tex[i] = tx[i]
            fw[i] = maxOf(1, w / divs[i])
            fh[i] = maxOf(1, h / divs[i])
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex[i])
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, fw[i], fh[i], 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null)
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo[i])
            GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, tex[i], 0)
            val st = GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER)
            if (st != GLES20.GL_FRAMEBUFFER_COMPLETE) Log.e(TAG, "fbo incomplete $st")
        }
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
    }
}
