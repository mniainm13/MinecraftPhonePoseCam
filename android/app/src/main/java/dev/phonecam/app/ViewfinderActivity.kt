package dev.phonecam.app

import android.annotation.SuppressLint
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.os.Build
import android.os.Bundle
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import dev.phonecam.app.stream.H264StreamClient

/**
 * Fullscreen debug viewfinder.
 * Main activity must release its H.264 client first (single TCP client).
 */
class ViewfinderActivity : AppCompatActivity(), TextureView.SurfaceTextureListener {

    private lateinit var textureView: TextureView
    private lateinit var debugStream: TextView
    private lateinit var debugVideo: TextView
    private lateinit var debugCodec: TextView
    private lateinit var debugFps: TextView

    private var h264: H264StreamClient? = null
    private var decodeSurface: Surface? = null
    private var pendingHost = "127.0.0.1"
    private var pendingPort = 8091
    private var videoW = 0
    private var videoH = 0
    private var renderHint = "–"
    private var started = false

    private val reconnect = object : Runnable {
        override fun run() {
            if (isFinishing || isDestroyed) return
            if (h264 == null && decodeSurface?.isValid == true && started) {
                startH264(decodeSurface!!)
            }
            textureView.postDelayed(this, 2000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_viewfinder)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        enterImmersiveMode()
        textureView = findViewById(R.id.viewfinderTexture)
        debugStream = findViewById(R.id.debugStream)
        debugVideo = findViewById(R.id.debugVideo)
        debugCodec = findViewById(R.id.debugCodec)
        debugFps = findViewById(R.id.debugFps)
        textureView.surfaceTextureListener = this

        pendingHost = intent.getStringExtra(EXTRA_HOST) ?: "127.0.0.1"
        pendingPort = intent.getIntExtra(EXTRA_PORT, 8091)
        debugStream.text = "stream: tcp://$pendingHost:$pendingPort"

        findViewById<View>(R.id.btnArDebug).setOnClickListener {
            startActivity(android.content.Intent(this, ArDebugActivity::class.java))
        }
        findViewById<View>(R.id.btnCloseDebug).setOnClickListener { finish() }

        textureView.postDelayed(reconnect, 2000L)
    }

    override fun onSurfaceTextureAvailable(st: SurfaceTexture, width: Int, height: Int) {
        applyCenterCrop()
        decodeSurface = Surface(st)
        started = true
        startH264(decodeSurface!!)
    }

    override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, width: Int, height: Int) {
        applyCenterCrop()
    }

    override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
        h264?.stop(); h264 = null
        decodeSurface?.release(); decodeSurface = null
        started = false
        return true
    }

    override fun onSurfaceTextureUpdated(st: SurfaceTexture) {
        // cheap fps hint from surface updates
        frames++
        val now = System.currentTimeMillis()
        if (now - fpsAt >= 1000) {
            debugFps.text = "render: ~$frames fps  crop=${videoW}x$videoH → view ${textureView.width}x${textureView.height}"
            frames = 0
            fpsAt = now
        }
    }

    private var frames = 0
    private var fpsAt = System.currentTimeMillis()

    private fun applyCenterCrop() {
        val vw = videoW; val vh = videoH
        val viewW = textureView.width; val viewH = textureView.height
        if (vw <= 0 || vh <= 0 || viewW <= 0 || viewH <= 0) return
        val videoAR = vw.toFloat() / vh
        val viewAR = viewW.toFloat() / viewH
        var scaleX = 1f; var scaleY = 1f
        if (videoAR > viewAR) scaleX = videoAR / viewAR else scaleY = viewAR / videoAR
        val m = Matrix()
        m.setScale(scaleX, scaleY, viewW / 2f, viewH / 2f)
        textureView.setTransform(m)
        debugVideo.text = "video: ${vw}x$vh  view: ${viewW}x$viewH  scale=${"%.2f".format(scaleX)}/${"%.2f".format(scaleY)}"
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) enterImmersiveMode()
    }

    @SuppressLint("ObsoleteSdkInt")
    private fun enterImmersiveMode() {
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false)
            val c = window.insetsController ?: return
            c.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
            c.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility =
                (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    or View.SYSTEM_UI_FLAG_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION)
        }
    }

    private fun startH264(surface: Surface) {
        if (h264 != null) return
        debugStream.text = "stream: connecting $pendingHost:$pendingPort…"
        val client = H264StreamClient(
            host = pendingHost, port = pendingPort, surface = surface,
            fallbackWidth = 1920, fallbackHeight = 1080,
        )
        client.onStatus = { s ->
            runOnUiThread {
                debugStream.text = "stream: $s"
                debugCodec.text = "codec: ${s}"
            }
        }
        client.onVideoSize = { w, h ->
            runOnUiThread {
                if (w > 0 && h > 0 && (w != videoW || h != videoH)) {
                    videoW = w; videoH = h; applyCenterCrop()
                }
            }
        }
        client.start()
        h264 = client
    }

    override fun onDestroy() {
        textureView.removeCallbacks(reconnect)
        h264?.stop()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_HOST = "host"
        const val EXTRA_PORT = "port"
    }
}
