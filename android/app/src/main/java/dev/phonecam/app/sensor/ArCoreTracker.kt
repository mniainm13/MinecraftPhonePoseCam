package dev.phonecam.app.sensor

import android.app.Activity
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES20
import android.util.Log
import com.google.ar.core.Anchor
import com.google.ar.core.ArCoreApk
import com.google.ar.core.Config
import com.google.ar.core.Frame
import com.google.ar.core.Plane
import com.google.ar.core.Session
import com.google.ar.core.TrackingState
import kotlin.concurrent.thread
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * True 6DoF via ARCore without preview: offscreen EGL pbuffer + Session.update().
 */
class ArCoreTracker(private val activity: Activity) {

    companion object {
        private const val TAG = "PhoneCamAR"
    }

    /** 1€ filter — reduce ARCore jitter without adding too much lag. */
    private class OneEuro(minCutoff: Float, beta: Float) {
        var minCutoff = minCutoff
        var beta = beta
        private var xPrev = 0f
        private var dxPrev = 0f
        private var init = false

        fun reset() {
            init = false
        }

        fun filter(value: Float, dt: Float): Float {
            if (dt <= 0f || dt > 0.2f) return value
            if (!init) {
                xPrev = value
                init = true
                return value
            }
            fun alpha(cut: Float): Float {
                val tau = 1f / (2f * Math.PI.toFloat() * cut)
                return 1f / (1f + tau / dt)
            }
            val dx = (value - xPrev) / dt
            val edx = alpha(1f) * dx + (1f - alpha(1f)) * dxPrev
            val cut = minCutoff + beta * abs(edx)
            val x = alpha(cut) * value + (1f - alpha(cut)) * xPrev
            xPrev = x
            dxPrev = edx
            return x
        }
    }

    private val fYaw = OneEuro(1.2f, 0.04f)
    private val fPitch = OneEuro(1.2f, 0.04f)
    private val fRoll = OneEuro(1.0f, 0.03f)
    private val fPx = OneEuro(1.5f, 0.02f)
    private val fPy = OneEuro(1.5f, 0.02f)
    private val fPz = OneEuro(1.5f, 0.02f)
    private var lastNs = 0L

    @Volatile var smooth = true
    @Volatile var metersPerBlock = 0.35f
    /** Extra gain on translation sent to MC (1.0 = raw scale). */
    @Volatile var posSensitivity = 1.0f
    /**
     * 0 = 更稳（滤波重、会锁静止）, 1 = 更跟手（更抖）.
     * 0.5 ≈ 默认；前台可调。
     */
    @Volatile var response = 0.5f
        set(value) {
            field = value.coerceIn(0f, 1f)
            applyResponse()
        }

    private fun applyResponse() {
        val r = response
        // 1€: higher minCutoff → less lag; higher beta → more jitter when moving
        val mc = 0.6f + r * 2.8f
        val beta = 0.008f + r * 0.09f
        fYaw.minCutoff = mc
        fYaw.beta = beta
        fPitch.minCutoff = mc
        fPitch.beta = beta
        fRoll.minCutoff = mc * 0.8f
        fRoll.beta = beta
        val pmc = mc * 1.2f
        fPx.minCutoff = pmc
        fPx.beta = beta * 0.5f
        fPy.minCutoff = pmc
        fPy.beta = beta * 0.5f
        fPz.minCutoff = pmc
        fPz.beta = beta * 0.5f
    }

    private var session: Session? = null
    private var installRequested = false
    private var running = false
    private var worker: Thread? = null

    // EGL offscreen
    private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE
    private var eglReady = false

    @Volatile var available = false
        private set

    /** True while AR worker loop is alive. */
    val isAlive: Boolean
        get() = running

    @Volatile var tracking = false
        private set

    // Debug / telemetry
    @Volatile var frameCount = 0L
    @Volatile var lastFrameNs = 0L
    @Volatile var updateFps = 0f
    @Volatile var stateName = "idle"
    @Volatile var lastError: String? = null
    @Volatile var hasPlane = false
    @Volatile var anchorOk = false
    @Volatile var frozenPos = false
    @Volatile var depthEnabled = false
    /** When false, skip Depth API — better when phone faces a bright monitor. */
    @Volatile var useDepth = true
    @Volatile var lastTrackingLossNs = 0L
    @Volatile private var requestRecalibrate = false
    private var wasTracking = false
    private var fpsWindow = 0L
    private var fpsLast = 0L

    private var originAnchor: Anchor? = null
    private var lastPx = 0f
    private var lastPy = 0f
    private var lastPz = 0f
    private var stillNs = 0L

    @Volatile var yaw = 0f
    @Volatile var pitch = 0f
    @Volatile var roll = 0f
    @Volatile var posX = 0f
    @Volatile var posY = 0f
    @Volatile var posZ = 0f

    private var originTx = 0f
    private var originTy = 0f
    private var originTz = 0f
    private var originYaw = 0f
    private var originPitch = 0f
    private var originRoll = 0f
    // Calibrated basis in ARCore world (OpenGL): right / up / forward
    private var orgRx = 1f; private var orgRy = 0f; private var orgRz = 0f
    private var orgUx = 0f; private var orgUy = 1f; private var orgUz = 0f
    private var orgFx = 0f; private var orgFy = 0f; private var orgFz = -1f
    private var hasOrigin = false

    /**
     * Non-blocking device support probe. False → caller must fall back to pure IMU.
     * Does not prompt install.
     */
    fun isDeviceSupported(): Boolean {
        return try {
            val a = ArCoreApk.getInstance().checkAvailability(activity)
            // Prefer isSupported; also allow "needs install / APK too old" so we can prompt.
            a.isSupported ||
                a == ArCoreApk.Availability.SUPPORTED_NOT_INSTALLED ||
                a == ArCoreApk.Availability.SUPPORTED_APK_TOO_OLD
        } catch (e: Exception) {
            Log.w(TAG, "checkAvailability", e)
            false
        }
    }

    fun ensureInstalled(): Boolean {
        if (!isDeviceSupported()) {
            available = false
            return false
        }
        return try {
            when (ArCoreApk.getInstance().requestInstall(activity, !installRequested)) {
                ArCoreApk.InstallStatus.INSTALLED -> {
                    available = true
                    true
                }
                ArCoreApk.InstallStatus.INSTALL_REQUESTED -> {
                    installRequested = true
                    false
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "ARCore install", e)
            available = false
            false
        }
    }

    private fun initEgl(): Boolean {
        if (eglReady) return true
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        if (eglDisplay == EGL14.EGL_NO_DISPLAY) return false
        val ver = IntArray(2)
        if (!EGL14.eglInitialize(eglDisplay, ver, 0, ver, 1)) return false

        val confAttr = intArrayOf(
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_DEPTH_SIZE, 16,
            EGL14.EGL_NONE,
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val num = IntArray(1)
        if (!EGL14.eglChooseConfig(eglDisplay, confAttr, 0, configs, 0, 1, num, 0)) return false
        val cfg = configs[0] ?: return false

        val surfAttr = intArrayOf(
            EGL14.EGL_WIDTH, 64,
            EGL14.EGL_HEIGHT, 64,
            EGL14.EGL_NONE,
        )
        eglSurface = EGL14.eglCreatePbufferSurface(eglDisplay, cfg, surfAttr, 0)
        if (eglSurface == EGL14.EGL_NO_SURFACE) return false

        val ctxAttr = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE)
        eglContext = EGL14.eglCreateContext(eglDisplay, cfg, EGL14.EGL_NO_CONTEXT, ctxAttr, 0)
        if (eglContext == EGL14.EGL_NO_CONTEXT) return false

        if (!EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) return false
        eglReady = true
        Log.i(TAG, "EGL pbuffer ready")
        return true
    }

    private fun releaseEgl() {
        if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
            EGL14.eglMakeCurrent(
                eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT
            )
            if (eglSurface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(eglDisplay, eglSurface)
            if (eglContext != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(eglDisplay, eglContext)
            EGL14.eglTerminate(eglDisplay)
        }
        eglDisplay = EGL14.EGL_NO_DISPLAY
        eglContext = EGL14.EGL_NO_CONTEXT
        eglSurface = EGL14.EGL_NO_SURFACE
        eglReady = false
    }

    fun start() {
        if (running) return
        if (!ensureInstalled()) return
        try {
            val s = Session(activity)
            val cfg = Config(s)
            cfg.updateMode = Config.UpdateMode.LATEST_CAMERA_IMAGE
            cfg.focusMode = Config.FocusMode.AUTO
            try {
                cfg.planeFindingMode = Config.PlaneFindingMode.HORIZONTAL
            } catch (_: Exception) {}
            // Depth API improves scale/drift on ToF phones; off when facing a screen
            try {
                if (useDepth && s.isDepthModeSupported(Config.DepthMode.AUTOMATIC)) {
                    cfg.depthMode = Config.DepthMode.AUTOMATIC
                    depthEnabled = true
                    Log.i(TAG, "Depth API on")
                } else {
                    cfg.depthMode = Config.DepthMode.DISABLED
                    depthEnabled = false
                }
            } catch (_: Exception) {
                depthEnabled = false
            }
            s.configure(cfg)
            session = s
            running = true
            available = true
            worker = thread(name = "phonecam-ar", isDaemon = true) {
                var watchdog: Thread? = null
                try {
                    if (!initEgl()) {
                        Log.e(TAG, "EGL init failed — ARCore will not produce frames")
                        available = false
                        return@thread
                    }
                    try {
                        s.resume()
                    } catch (e: Exception) {
                        Log.e(TAG, "resume", e)
                        return@thread
                    }
                    // ARCore TextureNotSetException unless a GL texture is bound as camera target
                    val tex = IntArray(1)
                    GLES20.glGenTextures(1, tex, 0)
                    GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex[0])
                    GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
                    GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
                    s.setCameraTextureName(tex[0])
                    s.setDisplayGeometry(0, 64, 64)

                    // Watchdog: Session.update() can stall; restart path if no frames
                    var lastProgressNs = System.nanoTime()
                    watchdog = thread(name = "phonecam-ar-watchdog", isDaemon = true) {
                        while (running) {
                            try { Thread.sleep(1000) } catch (_: InterruptedException) { break }
                            if (!running) break
                            val idle = System.nanoTime() - lastProgressNs
                            if (idle > 2_500_000_000L && available) {
                                Log.w(TAG, "AR stalled ${idle / 1_000_000}ms — force stop for restart")
                                running = false
                                tracking = false
                                lastError = "AR stall — restart needed"
                                break
                            }
                        }
                    }

                    while (running && !Thread.currentThread().isInterrupted) {
                        try {
                            val frame: Frame = s.update()
                            lastProgressNs = System.nanoTime()
                            val cam = frame.camera
                            tracking = cam.trackingState == TrackingState.TRACKING
                            stateName = cam.trackingState.toString()

                            // Recalibrate only on this GL/AR thread (Anchor.detach is not UI-safe)
                            if (requestRecalibrate) {
                                requestRecalibrate = false
                                try { originAnchor?.detach() } catch (_: Exception) {}
                                originAnchor = null
                                anchorOk = false
                                hasOrigin = false
                                frozenPos = false
                                stillNs = 0
                                lastPx = 0f; lastPy = 0f; lastPz = 0f
                                fYaw.reset(); fPitch.reset(); fRoll.reset()
                                fPx.reset(); fPy.reset(); fPz.reset()
                                posX = 0f; posY = 0f; posZ = 0f
                                yaw = 0f; pitch = 0f; roll = 0f
                                wasTracking = false
                                Log.i(TAG, "recalibrated on AR thread")
                            }

                            if (!tracking) {
                                wasTracking = false
                                Thread.sleep(16)
                                continue
                            }
                            // After tracking loss, force a fresh origin (new map / relocalize)
                            if (!wasTracking) {
                                lastTrackingLossNs = System.nanoTime()
                                hasOrigin = false
                                frozenPos = false
                                stillNs = 0
                                // Do NOT detach here either if avoidable — just drop ref after nulling
                                // Old anchor becomes garbage; detach only on clean stop
                                originAnchor = null
                                fYaw.reset(); fPitch.reset(); fRoll.reset()
                                fPx.reset(); fPy.reset(); fPz.reset()
                                lastPx = 0f; lastPy = 0f; lastPz = 0f
                                Log.i(TAG, "re-lock origin after tracking gap")
                            }
                            wasTracking = true

                            // Plane presence is a quality signal (not required for pose)
                            try {
                                val planes = s.getAllTrackables(Plane::class.java)
                                hasPlane = planes.any { it.trackingState == TrackingState.TRACKING }
                            } catch (_: Exception) {
                            }

                            val pose = cam.pose
                            val tx = pose.tx()
                            val ty = pose.ty()
                            val tz = pose.tz()

                            if (!hasOrigin) {
                                originTx = tx; originTy = ty; originTz = tz
                                val m0 = FloatArray(16)
                                pose.toMatrix(m0, 0)
                                // OpenGL column-major basis
                                orgRx = m0[0]; orgRy = m0[1]; orgRz = m0[2]
                                orgUx = m0[4]; orgUy = m0[5]; orgUz = m0[6]
                                // camera looks along -Z
                                orgFx = -m0[8]; orgFy = -m0[9]; orgFz = -m0[10]
                                originYaw = Math.toDegrees(atan2(-orgFx, -orgFz).toDouble()).toFloat()
                                originPitch = Math.toDegrees(asin((-orgFy).coerceIn(-1f, 1f)).toDouble()).toFloat()
                                originRoll = Math.toDegrees(atan2(m0[1], m0[5]).toDouble()).toFloat()
                                try {
                                    originAnchor = s.createAnchor(pose)
                                    anchorOk = originAnchor != null
                                } catch (e: Exception) {
                                    anchorOk = false
                                    Log.w(TAG, "anchor", e)
                                }
                                hasOrigin = true
                                Log.i(
                                    TAG,
                                    "origin f=(%.2f,%.2f,%.2f) r=(%.2f,%.2f,%.2f)".format(
                                        orgFx, orgFy, orgFz, orgRx, orgRy, orgRz
                                    )
                                )
                            }

                            // World translation relative to origin position
                            val wx = tx - originTx
                            val wy = ty - originTy
                            val wz = tz - originTz

                            // Project onto calibrated basis → phone-local meters
                            // +X right, +Y up, +Z forward (look at calibrate)
                            // Negate X: OpenGL right was mirrored vs MC right
                            val px = -(wx * orgRx + wy * orgRy + wz * orgRz)
                            val py = wx * orgUx + wy * orgUy + wz * orgUz
                            val pz = wx * orgFx + wy * orgFy + wz * orgFz

                            var azPos = pz // forward

                            // Stationary freeze (ZUPT-like): if delta tiny, hold pos
                            val now = System.nanoTime()
                            val dpos = sqrt(
                                (px - lastPx) * (px - lastPx) +
                                    (py - lastPy) * (py - lastPy) +
                                    (azPos - lastPz) * (azPos - lastPz)
                            )
                            if (dpos < 0.002f) { // ~2mm
                                stillNs += if (lastFrameNs > 0) now - lastFrameNs else 0
                                if (stillNs > 250_000_000L) {
                                    frozenPos = true
                                }
                            } else {
                                stillNs = 0
                                frozenPos = false
                                lastPx = px; lastPy = py; lastPz = azPos
                            }
                            if (!frozenPos) {
                                posX = px
                                posY = py
                                posZ = azPos
                            }

                            val m = FloatArray(16)
                            pose.toMatrix(m, 0)
                            // Camera looks along -Z
                            val fx = -m[8]; val fy = -m[9]; val fz = -m[10]
                            val rx = m[0]; val ry = m[1]
                            val uy = m[5]

                            // Yaw: 0 when looking -Z; left turn → +MC yaw
                            val yawR = atan2(-fx, -fz)
                            // Pitch: MC positive looks down; look-down => fy < 0
                            val pitchR = asin((-fy).coerceIn(-1f, 1f))
                            // Roll from right/up
                            val rollR = atan2(ry, uy)

                            if (hasOrigin && originYaw == 0f && originPitch == 0f && originRoll == 0f) {
                                // store origin angles on first tracking (hasOrigin already set above)
                            }
                            // Re-read origin angles only when we set them
                            // (originYaw etc. already set in !hasOrigin block — fix below)

                            var yDeg = Math.toDegrees(yawR.toDouble()).toFloat() - originYaw
                            var pDeg = Math.toDegrees(pitchR.toDouble()).toFloat() - originPitch
                            var rDeg = Math.toDegrees(rollR.toDouble()).toFloat() - originRoll
                            while (yDeg > 180f) yDeg -= 360f
                            while (yDeg < -180f) yDeg += 360f

                            val dt = if (lastNs == 0L) 0.016f
                            else (now - lastNs) * 1e-9f
                            lastNs = now

                            var outY = -yDeg
                            var outP = pDeg.coerceIn(-90f, 90f)
                            var outR = -rDeg
                            if (smooth) {
                                outY = fYaw.filter(outY, dt)
                                outP = fPitch.filter(outP, dt)
                                outR = fRoll.filter(outR, dt)
                            }
                            yaw = outY
                            pitch = outP
                            roll = outR

                            frameCount++
                            lastFrameNs = now
                            fpsWindow++
                            if (now - fpsLast >= 1_000_000_000L) {
                                updateFps = fpsWindow / ((now - fpsLast) * 1e-9f)
                                fpsWindow = 0
                                fpsLast = now
                            }
                            stateName = "TRACKING"
                        } catch (e: Exception) {
                            if (!running) break
                            lastError = e.javaClass.simpleName + ":" + e.message
                            Log.w(TAG, "AR update", e)
                            try { Thread.sleep(50) } catch (_: InterruptedException) { break }
                        }
                    }
                } finally {
                    try { watchdog?.interrupt() } catch (_: Exception) {}
                    watchdog = null
                    try { originAnchor?.detach() } catch (_: Exception) {}
                    originAnchor = null
                    try { s.pause() } catch (_: Exception) {}
                    try { s.close() } catch (_: Exception) {}
                    releaseEgl()
                    if (session === s) session = null
                    running = false
                    tracking = false
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "AR start failed", e)
            available = false
            running = false
        }
    }

    /** Non-blocking: worker exits and closes session on its own thread. */
    fun stop() {
        running = false
        // Do NOT interrupt or close Session here — Session.update() is native and can hang.
        tracking = false
    }

    /** Live toggle depth; applied on next start() or immediately if session running. */
    fun applyDepthMode(on: Boolean) {
        useDepth = on
        val s = session ?: return
        try {
            val cfg = s.config
            cfg.depthMode = if (on && s.isDepthModeSupported(Config.DepthMode.AUTOMATIC)) {
                Config.DepthMode.AUTOMATIC
            } else {
                Config.DepthMode.DISABLED
            }
            s.configure(cfg)
            depthEnabled = cfg.depthMode == Config.DepthMode.AUTOMATIC
            Log.i(TAG, "depth mode → $depthEnabled")
        } catch (e: Exception) {
            Log.w(TAG, "setDepth", e)
        }
    }

    /** Request origin re-zero. Safe from UI thread.
     * Actual Anchor detach + reset happens on the AR/GL thread next update.
     * Outputs are zeroed immediately so the UI feels instant.
     */
    fun calibrate() {
        requestRecalibrate = true
        hasOrigin = false
        frozenPos = false
        posX = 0f
        posY = 0f
        posZ = 0f
        yaw = 0f
        pitch = 0f
        roll = 0f
    }

    fun blockX() = (posX / metersPerBlock) * posSensitivity
    fun blockY() = (posY / metersPerBlock) * posSensitivity
    fun blockZ() = (posZ / metersPerBlock) * posSensitivity
}
