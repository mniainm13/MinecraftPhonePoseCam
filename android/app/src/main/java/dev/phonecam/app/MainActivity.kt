package dev.phonecam.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.opengl.GLSurfaceView
import android.view.Surface
import android.view.View
import android.view.WindowManager
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.EditText
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.slider.Slider
import dev.phonecam.app.net.StreamControlSender
import dev.phonecam.app.net.UdpPoseSender
import dev.phonecam.app.sensor.ArCoreTracker
import dev.phonecam.app.sensor.OrientationFusion
import dev.phonecam.app.stream.H264StreamClient
import dev.phonecam.app.ui.FrostBlurRenderer
import dev.phonecam.app.ui.FrostBlurView
import dev.phonecam.app.ui.ModePillBar
import dev.phonecam.app.ui.ZoomPillBar
import org.json.JSONArray
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.pow

/**
 * Camera-shell hub. Xiaomi-style chrome:
 * top status + ⌄ settings, zoom chips, mode 转向/自由, shutter.
 * Details HUD hidden unless enabled. Auto-reconnects H.264.
 */
class MainActivity : AppCompatActivity() {

    private val fusion by lazy { OrientationFusion(this) }
    private val ar by lazy { ArCoreTracker(this) }
    private val sender = UdpPoseSender()
    private val streamCtrl = StreamControlSender()
    private val prefs by lazy { getSharedPreferences("phonecam_ui", Context.MODE_PRIVATE) }

    private var streaming = false
    private var useAr = false
    private var showDetails = false
    private var zoom = 1f
    private var zoomMin = 0.25f
    private var zoomMax = 4f
    private var presets = mutableListOf(0.6f, 1f, 2f)
    private var streamBitrate = 12

    private lateinit var glView: GLSurfaceView
    private lateinit var frost: FrostBlurRenderer
    private var lastBlurBm: android.graphics.Bitmap? = null
    private var blurOn = true
    private lateinit var textStatus: TextView
    private lateinit var textPose: TextView
    private lateinit var statusDot: View
    private lateinit var btnExpand: ImageButton
    private lateinit var settingsPanel: View
    private lateinit var topChrome: View
    private lateinit var bottomChrome: View
    private lateinit var shutterCore: View
    private lateinit var btnShutter: View
    private lateinit var zoomPill: ZoomPillBar
    private lateinit var chipGroupPresets: ChipGroup
    private lateinit var modePill: ModePillBar
    private lateinit var seekZoom: Slider
    private lateinit var switchDetails: MaterialSwitch

    private var h264: H264StreamClient? = null
    private var decodeSurface: Surface? = null
    private var videoW = 0
    private var videoH = 0
    private var settingsOpen = false
    private var reconnecting = false

    private lateinit var scaleDetector: ScaleGestureDetector

    @Volatile private var yaw = 0f
    @Volatile private var pitch = 0f
    @Volatile private var roll = 0f

    private val camPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { startTracking() }

    private val packetCount = java.util.concurrent.atomic.AtomicInteger(0)
    private val lastUi = java.util.concurrent.atomic.AtomicLong(0)

    private val sendThread = object : Thread("phonecam-send") {
        override fun run() {
            while (!isInterrupted) {
                if (streaming) {
                    val y: Float
                    val p: Float
                    val r: Float
                    val bx: Float
                    val by: Float
                    val bz: Float
                    if (useAr) {
                        y = ar.yaw; p = ar.pitch; r = ar.roll
                        bx = ar.blockX(); by = ar.blockY(); bz = ar.blockZ()
                    } else {
                        y = yaw; p = pitch; r = roll
                        bx = 0f; by = 0f; bz = 0f
                    }
                    // Picture UV is ±90° in landscape; keep screen-right/up mapped to look + lateral pos.
                    // Roll is intentionally untouched (user: 横屏 roll 不用动).
                    val m = remapLandscapePose(y, p, r, bx, by, bz)
                    sender.send(m[0], m[1], m[2], m[3], m[4], m[5], zoom)
                    packetCount.incrementAndGet()
                    val now = System.currentTimeMillis()
                    if (showDetails && now - lastUi.get() > 160) {
                        lastUi.set(now)
                        val mode = if (useAr) "自由" else "转向"
                        val trk = if (useAr && ar.tracking) "●" else if (useAr) "…" else ""
                        runOnUiThread {
                            textPose.text =
                                "$mode$trk  yaw=%.1f pitch=%.1f roll=%.1f z=%.2f pkts=%d"
                                    .format(m[0], m[1], m[2], zoom, packetCount.get())
                        }
                    }
                }
                try { sleep(16L) } catch (_: InterruptedException) { break }
            }
        }
    }

    /** Restart H.264 if decoder dies. */
    private val reconnectRunnable = object : Runnable {
        override fun run() {
            if (isFinishing || isDestroyed) return
            if (h264 == null && decodeSurface?.isValid == true) {
                startH264(decodeSurface!!)
            }
            glView.postDelayed(this, 2500L)
        }
    }

    /**
     * Landscape crop rotates picture UV ±90°. Remap look + lateral pos so
     * screen-right/up still drive yaw/pitch and dx/dy the same way as portrait.
     * Roll is passed through unchanged.
     * flip=false (UV+90°): yaw'=-pitch, pitch'=yaw, dx'=-dy, dy'=dx
     * flip=true  (UV-90°): yaw'=pitch, pitch'=-yaw, dx'=dy, dy'=-dx
     */
    private fun remapLandscapePose(
        yawIn: Float,
        pitchIn: Float,
        rollIn: Float,
        dxIn: Float,
        dyIn: Float,
        dzIn: Float,
    ): FloatArray {
        if (frost.cropMode != 1) {
            return floatArrayOf(yawIn, pitchIn, rollIn, dxIn, dyIn, dzIn)
        }
        return if (frost.landscapeFlip) {
            floatArrayOf(pitchIn, -yawIn, rollIn, dyIn, -dxIn, dzIn)
        } else {
            floatArrayOf(-pitchIn, yawIn, rollIn, -dyIn, dxIn, dzIn)
        }
    }

    /** Restart AR if watchdog killed the session. */
    private val arWatch = object : Runnable {
        override fun run() {
            if (isFinishing || isDestroyed) return
            if (streaming && useAr && !ar.isAlive) {
                ar.start()
            }
            glView.postDelayed(this, 1500L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_main)

        loadPrefs()
        bindViews()
        applyWindowInsets()
        setupGestures()
        setupControls()
        rebuildZoomChips()
        rebuildPresetEditor()
        applyBottomHeight(prefs.getFloat("bottom_ui", 1f))

        sendThread.start()
        glView.postDelayed(reconnectRunnable, 2500L)
        glView.postDelayed(arWatch, 1500L)

        startStreaming()
    }

    private fun loadPrefs() {
        showDetails = prefs.getBoolean("show_details", false)
        zoomMin = prefs.getFloat("zoom_min", 0.25f)
        zoomMax = prefs.getFloat("zoom_max", 4f)
        streamBitrate = prefs.getInt("bitrate", 12)
        val raw = prefs.getString("presets", null)
        presets = if (raw.isNullOrBlank()) {
            mutableListOf(0.6f, 1f, 2f)
        } else {
            try {
                val arr = JSONArray(raw)
                (0 until arr.length()).map { arr.getDouble(it).toFloat() }.toMutableList()
            } catch (_: Exception) {
                mutableListOf(0.6f, 1f, 2f)
            }
        }
        sortPresets()
    }

    private fun savePrefs() {
        prefs.edit()
            .putBoolean("show_details", showDetails)
            .putFloat("zoom_min", zoomMin)
            .putFloat("zoom_max", zoomMax)
            .putInt("bitrate", streamBitrate)
            .putString("presets", JSONArray(presets).toString())
            .apply()
    }

    private fun sortPresets() {
        presets = presets.distinct().sorted().toMutableList()
        // UI rebuild only after bindViews
        if (this::zoomPill.isInitialized) rebuildZoomChips()
    }

    private fun bindViews() {
        glView = findViewById(R.id.viewfinderGl)
        frost = FrostBlurRenderer()
        glView.setEGLContextClientVersion(2)
        glView.setRenderer(frost)
        glView.renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
        // Keep EGL + SurfaceTexture across pause (debug viewfinder) so resume is not black
        glView.preserveEGLContextOnPause = true
        frost.setSurfaceReady { s: Surface ->
            runOnUiThread {
                decodeSurface = s
                startH264IfNeeded()
            }
        }
        frost.setBlurSink { bm: android.graphics.Bitmap, fw: Int, fh: Int ->
            runOnUiThread { pushBlur(bm, fw, fh) }
        }
        blurOn = prefs.getBoolean("blur_on", true)
        frost.level = 2 // always strong; no strength slider
        frost.intervalMs = (1000L / prefs.getInt("blur_fps", 20).coerceIn(10, 60))
        // Match previous FrostBlur / capsule radii
        findViewById<FrostBlurView>(R.id.blurZoom)?.setCornerDp(22f)
        findViewById<FrostBlurView>(R.id.blurMode)?.setCornerDp(20f)
        findViewById<FrostBlurView>(R.id.blurCal)?.setCornerDp(24f)
        findViewById<FrostBlurView>(R.id.blurSheet)?.setCornerDp(24f)
        findViewById<FrostBlurView>(R.id.settingsPanel)?.setCornerDp(28f)
        findViewById<FrostBlurView>(R.id.settingsPanel)?.setFallbackColor(0xCC000000.toInt())

        textStatus = findViewById(R.id.textStatus)
        textPose = findViewById(R.id.textPose)
        statusDot = findViewById(R.id.statusDot)
        btnExpand = findViewById(R.id.btnExpand)
        settingsPanel = findViewById(R.id.settingsPanel)
        topChrome = findViewById(R.id.topChrome)
        bottomChrome = findViewById(R.id.bottomChrome)
        shutterCore = findViewById(R.id.shutterCore)
        btnShutter = findViewById(R.id.btnShutter)
        zoomPill = findViewById(R.id.zoomPill)
        chipGroupPresets = findViewById(R.id.chipGroupPresets)
        modePill = findViewById(R.id.modePill)
        seekZoom = findViewById(R.id.seekZoom)
        switchDetails = findViewById(R.id.switchDetails)

        findViewById<EditText>(R.id.inputHost).setText(prefs.getString("host", ""))
        findViewById<EditText>(R.id.inputZoomMin).setText(zoomMin.toString())
        findViewById<EditText>(R.id.inputZoomMax).setText(zoomMax.toString())
        findViewById<Slider>(R.id.seekBitrate).value = streamBitrate.toFloat()
        findViewById<TextView>(R.id.labelBitrate).text = "推流码率 $streamBitrate Mbps"
        val bottomUi = prefs.getFloat("bottom_ui", 1f)
        findViewById<Slider>(R.id.seekBottomUi).value = bottomUi
        findViewById<TextView>(R.id.labelBottomUi).text = "底部 UI 距底 ×%.1f".format(bottomUi)
        switchDetails.isChecked = showDetails
        textPose.visibility = if (showDetails) View.VISIBLE else View.GONE
        findViewById<MaterialSwitch>(R.id.switchBlur).isChecked = blurOn
        val bf = prefs.getInt("blur_fps", 20)
        findViewById<Slider>(R.id.seekBlurFps).value = bf.toFloat()
        findViewById<TextView>(R.id.labelBlurFps).text = "模糊帧率 $bf fps"
        seekZoom.valueFrom = 0f
        seekZoom.valueTo = 1f
        seekZoom.value = zoomToNorm(1f.coerceIn(zoomMin, zoomMax))
    }

    private fun applySavedFrame() {
        frost.cropMode = prefs.getInt("crop_mode", 0)
        frost.frameAr = prefs.getFloat("frame_ar", 0f)
        frost.landscapeFlip = prefs.getBoolean("landscape_flip", false)
        frost.rotationDeg = prefs.getFloat("rotate_deg", 0f)
    }

    private fun setupCropAndFrame() {
        val group = findViewById<com.google.android.material.button.MaterialButtonToggleGroup>(R.id.groupCropMode)
        val flipRow = findViewById<android.widget.LinearLayout>(R.id.rowLandscapeFlip)
        val flipGroup = findViewById<com.google.android.material.button.MaterialButtonToggleGroup>(R.id.groupLandscapeFlip)
        fun updateFlipVisibility() {
            flipRow.visibility = if (frost.cropMode == 1) View.VISIBLE else View.GONE
        }
        if (frost.cropMode == 1) group.check(R.id.btnCropLandscape) else group.check(R.id.btnCropPortrait)
        updateFlipVisibility()
        group.addOnButtonCheckedListener { _, id, checked ->
            if (!checked) return@addOnButtonCheckedListener
            frost.cropMode = if (id == R.id.btnCropLandscape) 1 else 0
            prefs.edit().putInt("crop_mode", frost.cropMode).apply()
            updateFlipVisibility()
        }
        if (frost.landscapeFlip) flipGroup.check(R.id.btnFlipOn) else flipGroup.check(R.id.btnFlipOff)
        flipGroup.addOnButtonCheckedListener { _, id, checked ->
            if (!checked) return@addOnButtonCheckedListener
            frost.landscapeFlip = id == R.id.btnFlipOn
            prefs.edit().putBoolean("landscape_flip", frost.landscapeFlip).apply()
        }
        val chips = findViewById<ChipGroup>(R.id.chipFrame)
        when {
            frost.frameAr == 1f -> chips.check(R.id.chipFrame11)
            kotlin.math.abs(frost.frameAr - 0.75f) < 0.01f -> chips.check(R.id.chipFrame34)
            kotlin.math.abs(frost.frameAr - 16f / 9f) < 0.01f -> chips.check(R.id.chipFrame169)
            frost.frameAr > 2.3f -> chips.check(R.id.chipFrameCinema)
            else -> chips.check(R.id.chipFrameFull)
        }
        chips.setOnCheckedStateChangeListener { _, ids ->
            val ar = when (ids.firstOrNull()) {
                R.id.chipFrame11 -> 1f
                R.id.chipFrame34 -> 0.75f
                R.id.chipFrame169 -> 16f / 9f
                R.id.chipFrameCinema -> 2.39f
                else -> 0f
            }
            frost.frameAr = ar
            prefs.edit().putFloat("frame_ar", ar).apply()
        }
    }

    private fun blurLevelLabel(l: Int) = when (l) {
        0 -> "模糊强度 关"
        1 -> "模糊强度 弱"
        else -> "模糊强度 强"
    }

    /** Drop blur bitmap so FrostBlurView falls back to translucent fill. */
    private fun clearFrostBlur() {
        listOf(R.id.blurZoom, R.id.blurMode, R.id.blurCal, R.id.blurSheet, R.id.settingsPanel)
            .forEach { id ->
                findViewById<FrostBlurView>(id)?.setBlur(null, 1, 1, 0f, 0f)
                findViewById<FrostBlurView>(id)?.invalidate()
            }
        lastBlurBm?.recycle()
        lastBlurBm = null
    }

    private fun pushBlur(bm: android.graphics.Bitmap, fw: Int, fh: Int) {
        if (!blurOn || frost.level <= 0) {
            bm.recycle()
            return
        }
        lastBlurBm?.recycle()
        lastBlurBm = bm
        val targets = listOf(
            findViewById<FrostBlurView>(R.id.blurZoom),
            findViewById<FrostBlurView>(R.id.blurMode),
            findViewById<FrostBlurView>(R.id.blurCal),
            findViewById<FrostBlurView>(R.id.blurSheet),
            findViewById<FrostBlurView>(R.id.settingsPanel),
        )
        for (t in targets) {
            if (t == null) continue
            val loc = IntArray(2)
            t.getLocationInWindow(loc)
            // GL view is fullscreen under chrome; map window coords directly
            t.setBlur(bm, fw, fh, loc[0].toFloat(), loc[1].toFloat())
        }
    }

    private fun applyWindowInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(topChrome) { v, insets ->
            val b = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            v.updatePadding(top = b.top + dp(6), left = b.left + dp(12), right = b.right + dp(12))
            insets
        }
        ViewCompat.setOnApplyWindowInsetsListener(bottomChrome) { v, insets ->
            val b = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            v.updatePadding(bottom = b.bottom + dp(10), left = b.left + dp(12), right = b.right + dp(12))
            insets
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun setupGestures() {
        scaleDetector = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(d: ScaleGestureDetector): Boolean {
                // Non-linear: cube root-ish response for fine control near 1×
                val factor = d.scaleFactor
                val curved = if (factor >= 1f) 1f + (factor - 1f) * 0.85f
                else 1f - (1f - factor) * 0.85f
                setZoom(zoom * curved, animate = false)
                return true
            }
        })
        glView.setOnTouchListener { _, e ->
            scaleDetector.onTouchEvent(e)
            true
        }
    }

    /** Non-linear remap: more steps near 1×. */
    private fun zoomToNorm(z: Float): Float {
        val zc = z.coerceIn(zoomMin, zoomMax)
        val mid = 1f
        return if (zc <= mid) {
            val t = (zc - zoomMin) / (mid - zoomMin).coerceAtLeast(1e-4f)
            0.5f * t
        } else {
            val t = (zc - mid) / (zoomMax - mid).coerceAtLeast(1e-4f)
            0.5f + 0.5f * t
        }
    }

    private fun normToZoom(n: Float): Float {
        val nn = n.coerceIn(0f, 1f)
        val mid = 1f
        return if (nn <= 0.5f) {
            val t = nn / 0.5f
            // ease-in for sub-1
            zoomMin + (mid - zoomMin) * t.pow(0.85f)
        } else {
            val t = (nn - 0.5f) / 0.5f
            mid + (zoomMax - mid) * t.pow(1.15f)
        }
    }

    private fun setZoom(z: Float, animate: Boolean) {
        val target = z.coerceIn(zoomMin, zoomMax)
        if (animate) {
            zoomPill.animateTo(target)
            zoom = target
        } else {
            zoom = target
            zoomPill.setZoom(target, notify = false)
        }
        updateZoomUi()
    }

    private fun updateZoomUi() {
        findViewById<TextView>(R.id.labelZoom)?.text = "当前缩放 ×%.2f".format(zoom)
        // keep pill in sync when driven from settings slider
        if (abs(zoomPill.zoom - zoom) > 1e-3f) zoomPill.setZoom(zoom, notify = false)
        val sv = zoomToNorm(zoom)
        if (abs(seekZoom.value - sv) > 0.001f) {
            try { seekZoom.value = sv } catch (_: Exception) {}
        }
    }

    private fun rebuildZoomChips() {
        zoomPill.presets = presets.toList()
        zoomPill.zoomMin = zoomMin
        zoomPill.zoomMax = zoomMax
        zoomPill.setZoom(zoom, notify = false)
        zoomPill.onZoom = { z ->
            zoom = z
            findViewById<TextView>(R.id.labelZoom)?.text = "当前缩放 ×%.2f".format(zoom)
        }
    }

    private fun rebuildPresetEditor() {
        chipGroupPresets.removeAllViews()
        presets.forEachIndexed { idx, p ->
            val c = Chip(this).apply {
                text = if (abs(p - p.toInt()) < 0.05f) "${p.toInt()}×"
                else if (abs(p * 10 - (p * 10).toInt()) < 0.05f) "%.1f×".format(p)
                else "%.2f×".format(p)
                isCloseIconVisible = true
                setTextColor(0xFFFFFFFF.toInt())
                chipBackgroundColor =
                    android.content.res.ColorStateList.valueOf(0x4D000000.toInt())
                closeIconTint = android.content.res.ColorStateList.valueOf(0xFFFFFFFF.toInt())
                setOnCloseIconClickListener {
                    if (presets.size > 1) {
                        presets.removeAt(idx)
                        sortPresets()
                        savePrefs()
                        rebuildPresetEditor()
                        rebuildZoomChips()
                    } else {
                        Toast.makeText(this@MainActivity, "至少保留一个挡位", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            chipGroupPresets.addView(c)
        }
    }

    private fun setupControls() {
        btnExpand.setOnClickListener { toggleSettings() }
        // No scrim layer — tap card edge area is scroll only; close via chevron
        findViewById<View>(R.id.btnArDebug).setOnClickListener {
            startActivity(android.content.Intent(this, ArDebugActivity::class.java))
        }
        findViewById<View>(R.id.btnSheet).setOnClickListener { openFullscreenViewfinder() }
        btnShutter.setOnClickListener { if (streaming) stopStreaming() else startStreaming() }
        findViewById<View>(R.id.btnCalibrate).setOnClickListener {
            fusion.calibrate(); ar.calibrate(); toast("已校准")
        }

        modePill.items = listOf("转向", "自由")
        modePill.selected = if (useAr) 1 else 0
        modePill.onSelect = { i -> switchMode(i == 1) }

        switchDetails.setOnCheckedChangeListener { _, on ->
            showDetails = on
            textPose.visibility = if (on) View.VISIBLE else View.GONE
            savePrefs()
        }
        findViewById<MaterialSwitch>(R.id.switchBlur).setOnCheckedChangeListener { _, on ->
            blurOn = on
            frost.level = if (on) 2 else 0
            prefs.edit().putBoolean("blur_on", on).apply()
            if (!on) clearFrostBlur()
        }
        findViewById<Slider>(R.id.seekBlurFps).addOnChangeListener { _, v, from ->
            if (from) {
                val fps = v.toInt()
                findViewById<TextView>(R.id.labelBlurFps).text = "模糊帧率 $fps fps"
                frost.intervalMs = (1000L / fps.coerceIn(10, 60))
                prefs.edit().putInt("blur_fps", fps).apply()
            }
        }
        applySavedFrame()
        setupCropAndFrame()

        findViewById<Slider>(R.id.seekSensitivity).addOnChangeListener { _, v, from ->
            if (from) {
                fusion.sensitivity = v
                findViewById<TextView>(R.id.labelSens).text = "灵敏度 ×%.1f".format(v)
            }
        }
        findViewById<Slider>(R.id.seekPosSens).addOnChangeListener { _, v, from ->
            if (from) {
                ar.posSensitivity = v
                findViewById<TextView>(R.id.labelPosSens).text = "位移灵敏度 ×%.1f（自由模式）".format(v)
            }
        }
        findViewById<Slider>(R.id.seekBitrate).addOnChangeListener { _, v, from ->
            if (from) {
                streamBitrate = v.toInt()
                findViewById<TextView>(R.id.labelBitrate).text = "推流码率 $streamBitrate Mbps"
                streamCtrl.send(host(), 8091, streamBitrate, 30)
                savePrefs()
            }
        }
        findViewById<Slider>(R.id.seekBottomUi)?.addOnChangeListener { _, v, from ->
            if (from) {
                findViewById<TextView>(R.id.labelBottomUi).text = "底部 UI 距底 ×%.1f".format(v)
                applyBottomHeight(v)
                prefs.edit().putFloat("bottom_ui", v).apply()
            }
        }

        // seekZoom here is normalized 0..1 mapped non-linearly to zoomMin..zoomMax
        seekZoom.addOnChangeListener { _, v, from ->
            if (from) setZoom(normToZoom(v), animate = false)
        }

        findViewById<View>(R.id.btnAddPreset).setOnClickListener {
            val et = findViewById<EditText>(R.id.inputNewPreset)
            val v = et.text?.toString()?.toFloatOrNull()
            if (v == null || v < 0.1f || v > 20f) {
                toast("请输入 0.1–20 的数值")
                return@setOnClickListener
            }
            presets.add(v)
            sortPresets()
            savePrefs()
            rebuildPresetEditor()
            rebuildZoomChips()
            et.setText("")
        }

        listOf(R.id.inputZoomMin, R.id.inputZoomMax).forEach { id ->
            findViewById<EditText>(id).setOnFocusChangeListener { _, has ->
                if (!has) applyZoomRangeFromInputs()
            }
        }
    }

    private fun applyZoomRangeFromInputs() {
        val minV = findViewById<EditText>(R.id.inputZoomMin).text?.toString()?.toFloatOrNull() ?: zoomMin
        val maxV = findViewById<EditText>(R.id.inputZoomMax).text?.toString()?.toFloatOrNull() ?: zoomMax
        if (minV >= maxV || minV < 0.05f) {
            toast("缩放范围无效")
            return
        }
        zoomMin = minV
        zoomMax = maxV
        seekZoom.valueFrom = 0f
        seekZoom.valueTo = 1f
        zoom = zoom.coerceIn(zoomMin, zoomMax)
        updateZoomUi()
        savePrefs()
    }

    /** FrostBlurRenderer owns SurfaceTexture; no TextureView transform needed. */
    private fun applyPreviewTransform() = Unit

    /** Distance of bottom UI stack from screen bottom. */
    private fun applyBottomHeight(f: Float) {
        val k = f.coerceIn(0.4f, 2.0f)
        // 0.4 → 16dp, 1.0 → 40dp, 2.0 → 80dp
        val margin = (k * 40f).toInt()
        val bv = findViewById<View>(R.id.bottomChrome) ?: return
        val lp = bv.layoutParams as? androidx.constraintlayout.widget.ConstraintLayout.LayoutParams
            ?: return
        lp.bottomMargin = dp(margin)
        bv.layoutParams = lp
        bv.requestLayout()
    }

    private fun host(): String =
        findViewById<EditText>(R.id.inputHost).text?.toString()?.trim().orEmpty().ifEmpty { "127.0.0.1" }

    private fun toggleSettings() {
        settingsOpen = !settingsOpen
        val v = settingsPanel
        v.animate().cancel()
        if (settingsOpen) {
            v.visibility = View.VISIBLE
            v.alpha = 0f
            v.translationY = -12f
            v.animate().alpha(1f).translationY(0f).setDuration(180).start()
        } else {
            v.animate().alpha(0f).translationY(-8f).setDuration(140)
                .withEndAction {
                    v.visibility = View.GONE
                    v.translationY = 0f
                }.start()
        }
        btnExpand.setImageResource(
            if (settingsOpen) R.drawable.ic_chevron_up else R.drawable.ic_chevron_down
        )
    }

    private fun switchMode(arOn: Boolean) {
        val was = streaming
        if (was) {
            ar.stop(); fusion.stop()
        }
        useAr = arOn
        if (this::modePill.isInitialized) modePill.selected = if (arOn) 1 else 0
        findViewById<Slider>(R.id.seekPosSens).isEnabled = arOn
        findViewById<TextView>(R.id.labelPosSens).alpha = if (arOn) 1f else 0.4f
        if (was) startTracking()
        updateStatus("模式：" + if (arOn) "自由 6DoF" else "转向")
    }

    private fun startStreaming() {
        val host = host()
        val port = findViewById<EditText>(R.id.inputPort).text?.toString()?.toIntOrNull() ?: 42424
        prefs.edit().putString("host", host).apply()
        sender.host = host
        sender.port = port
        sender.start()
        packetCount.set(0)
        streaming = true
        shutterCore.setBackgroundResource(R.drawable.shutter_core_rec)
        startTracking()
        updateStatus("推流 $host:$port")
        streamCtrl.send(host, 8091, streamBitrate, 30)
        startH264IfNeeded()
    }

    private fun stopStreaming() {
        streaming = false
        fusion.stop(); ar.stop(); sender.stop()
        shutterCore.setBackgroundResource(R.drawable.shutter_core_idle)
        updateStatus("已停止")
    }

    private fun startTracking() {
        if (useAr && ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) {
            ar.calibrate(); ar.start()
            if (ar.available) {
                fusion.stop()
                return
            }
        }
        if (useAr && ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED
        ) {
            camPermission.launch(Manifest.permission.CAMERA)
            return
        }
        useAr = false
        if (this::modePill.isInitialized) modePill.selected = 0
        fusion.start(OrientationFusion.Callback { y, p, r ->
            yaw = y; pitch = p; roll = r
        })
    }

    private fun updateStatus(s: String) {
        textStatus.text = s
    }

    private fun setDot(online: Boolean, error: Boolean = false) {
        statusDot.setBackgroundResource(
            when {
                error -> R.drawable.dot_error
                online -> R.drawable.dot_online
                else -> R.drawable.dot_offline
            }
        )
    }

    private fun openFullscreenViewfinder() {
        streamCtrl.send(host(), 8091, streamBitrate, 30)
        // Server accepts one TCP client — free the slot before debug viewfinder
        h264?.stop()
        h264 = null
        startActivity(
            android.content.Intent(this, ViewfinderActivity::class.java)
                .putExtra(ViewfinderActivity.EXTRA_HOST, host())
                .putExtra(ViewfinderActivity.EXTRA_PORT, 8091)
        )
    }

    private fun startH264IfNeeded() {
        val surface = decodeSurface
        if (surface == null || !surface.isValid) return
        if (h264 != null) return
        startH264(surface)
    }

    override fun onResume() {
        super.onResume()
        glView.onResume()
        // After debug viewfinder, H264 was stopped to free TCP slot — restart
        h264?.stop()
        h264 = null
        glView.postDelayed({ startH264IfNeeded() }, 200)
    }

    override fun onPause() {
        glView.onPause()
        super.onPause()
    }

    private fun startH264(surface: Surface) {
        if (reconnecting) return
        val client = H264StreamClient(
            host = host(),
            port = 8091,
            surface = surface,
            fallbackWidth = 1920,
            fallbackHeight = 1080,
        )
        client.onStatus = { s ->
            runOnUiThread {
                val ok = s.contains("解码") || s.contains("已连接")
                val err = s.contains("错误") || s.contains("失败")
                setDot(online = ok, error = err)
                if (showDetails) textStatus.text = s
            }
        }
        client.onVideoSize = { w, h ->
            runOnUiThread {
                if (w > 0 && h > 0 && (w != videoW || h != videoH)) {
                    videoW = w; videoH = h
                    frost.setVideoSize(w, h)
                    applyPreviewTransform()
                }
            }
        }
        // Wrap: if worker ends, clear client so reconnect can fire
        client.start()
        h264 = client
        Thread {
            try {
                while (!Thread.currentThread().isInterrupted) {
                    Thread.sleep(1000)
                    // Heuristic: if client reported idle/failed via status field
                    val st = client.status
                    if (st.startsWith("错误") || st.contains("流结束") || st.contains("连接中断") || st.contains("解码器失败")) {
                        runOnUiThread {
                            if (h264 === client) {
                                client.stop()
                                h264 = null
                                setDot(false, error = true)
                                if (!reconnecting) {
                                    reconnecting = true
                                    updateStatus("重连中…")
                                    glView.postDelayed({
                                        reconnecting = false
                                        startH264IfNeeded()
                                        if (h264 == null) updateStatus("等待画面")
                                    }, 2000)
                                }
                            }
                        }
                        break
                    }
                }
            } catch (_: Exception) {}
        }.apply { isDaemon = true; start() }
    }

    override fun onDestroy() {
        stopStreaming()
        sendThread.interrupt()
        h264?.stop()
        streamCtrl.close()
        glView.removeCallbacks(reconnectRunnable)
        glView.removeCallbacks(arWatch)
        frost.releaseDecode()
        lastBlurBm?.recycle()
        lastBlurBm = null
        super.onDestroy()
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}
