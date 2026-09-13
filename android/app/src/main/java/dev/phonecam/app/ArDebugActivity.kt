package dev.phonecam.app

import android.os.Bundle
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.materialswitch.MaterialSwitch
import dev.phonecam.app.sensor.ArCoreTracker

/** Live ARCore telemetry — see if tracking/fps/scale look right. */
class ArDebugActivity : AppCompatActivity() {

    private lateinit var ar: ArCoreTracker
    private lateinit var textState: TextView
    private lateinit var textPose: TextView
    private lateinit var labelScale: TextView
    private var started = false
    private val ui = android.os.Handler(android.os.Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            val fps = ar.updateFps
            textState.text = "state=${ar.stateName}  tracking=${ar.tracking}  fps=%.1f  frames=%d  depth=${ar.depthEnabled}  plane=${ar.hasPlane}  err=${ar.lastError ?: "-"}"
                .format(fps, ar.frameCount)
            textPose.text = "yaw=%7.2f  pitch=%7.2f  roll=%7.2f\npos=(%.3f, %.3f, %.3f) m\nblk=(%.2f, %.2f, %.2f)"
                .format(
                    ar.yaw, ar.pitch, ar.roll,
                    ar.posX, ar.posY, ar.posZ,
                    ar.blockX(), ar.blockY(), ar.blockZ(),
                )
            ui.postDelayed(this, 200)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_ar_debug)
        ar = ArCoreTracker(this)
        textState = findViewById(R.id.textArState)
        textPose = findViewById(R.id.textArPose)
        labelScale = findViewById(R.id.labelScale)

        findViewById<MaterialButton>(R.id.btnArToggle).setOnClickListener {
            if (started) {
                ar.stop()
                started = false
                (it as MaterialButton).text = "启动 AR"
            } else {
                ar.calibrate()
                ar.start()
                started = true
                (it as MaterialButton).text = "停止 AR"
            }
        }
        findViewById<MaterialButton>(R.id.btnArCal).setOnClickListener { ar.calibrate() }
        findViewById<MaterialSwitch>(R.id.switchSmooth).setOnCheckedChangeListener { _, c ->
            ar.smooth = c
        }
        findViewById<MaterialSwitch>(R.id.switchDepth).setOnCheckedChangeListener { _, c ->
            ar.applyDepthMode(c)
        }
        findViewById<SeekBar>(R.id.seekScale).setOnSeekBarChangeListener(
            object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, u: Boolean) {
                    // 1..100 → 0.05 .. 1.00 m/block
                    val m = 0.05f + p * 0.0095f
                    ar.metersPerBlock = m
                    labelScale.text = "尺度 meters/block = %.2f".format(m)
                }
                override fun onStartTrackingTouch(sb: SeekBar?) = Unit
                override fun onStopTrackingTouch(sb: SeekBar?) = Unit
            }
        )

        val labelResp = findViewById<TextView>(R.id.labelResponse)
        findViewById<SeekBar>(R.id.seekResponse).setOnSeekBarChangeListener(
            object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, u: Boolean) {
                    ar.response = p / 100f
                    labelResp.text = "响应/稳定 = %d%%（0稳 100跟手）".format(p)
                }
                override fun onStartTrackingTouch(sb: SeekBar?) = Unit
                override fun onStopTrackingTouch(sb: SeekBar?) = Unit
            }
        )
    }

    override fun onResume() {
        super.onResume()
        ui.post(tick)
    }

    override fun onPause() {
        ui.removeCallbacks(tick)
        if (started) {
            ar.stop()
            started = false
        }
        super.onPause()
    }
}
