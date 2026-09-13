package dev.phonecam.app

import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.slider.Slider
import dev.phonecam.app.ui.CropOverlayView

/**
 * Edit crop rect + rotation. Result: RESULT_OK extras crop_u0..v1, rotate_deg.
 */
class CropEditActivity : AppCompatActivity() {

    private lateinit var overlay: CropOverlayView
    private var rotateDeg = 0f

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_crop_edit)

        overlay = findViewById(R.id.cropOverlay)
        val labelRot = findViewById<TextView>(R.id.labelRotate)
        val inputRot = findViewById<EditText>(R.id.inputRotate)
        val seekRot = findViewById<Slider>(R.id.seekRotate)
        val chips = findViewById<ChipGroup>(R.id.chipAspect)

        val u0 = intent.getFloatExtra(EXTRA_U0, 0f)
        val v0 = intent.getFloatExtra(EXTRA_V0, 0f)
        val u1 = intent.getFloatExtra(EXTRA_U1, 1f)
        val v1 = intent.getFloatExtra(EXTRA_V1, 1f)
        rotateDeg = intent.getFloatExtra(EXTRA_ROT, 0f)

        if (u1 - u0 > 0.02f && v1 - v0 > 0.02f) {
            overlay.setCropNorm(u0, v0, u1, v1)
        }
        seekRot.value = rotateDeg.coerceIn(-180f, 180f)
        labelRot.text = "旋转 %.0f°".format(rotateDeg)
        inputRot.setText("%.1f".format(rotateDeg))

        fun setRotate(v: Float) {
            rotateDeg = v.coerceIn(-180f, 180f)
            labelRot.text = "旋转 %.0f°".format(rotateDeg)
            inputRot.setText("%.1f".format(rotateDeg))
            seekRot.value = rotateDeg
        }

        seekRot.addOnChangeListener { _, v, from -> if (from) setRotate(v) }
        inputRot.setOnFocusChangeListener { _, has ->
            if (!has) setRotate(inputRot.text?.toString()?.toFloatOrNull() ?: 0f)
        }

        chips.setOnCheckedStateChangeListener { _, ids ->
            when (ids.firstOrNull()) {
                R.id.chipFree -> overlay.aspect = null
                R.id.chip169 -> overlay.aspect = 16f / 9f
                R.id.chip916 -> overlay.aspect = 9f / 16f
                R.id.chip11 -> overlay.aspect = 1f
                R.id.chipFull -> {
                    overlay.aspect = null
                    overlay.resetFull()
                }
            }
            overlay.applyAspectFromCenter()
            overlay.invalidate()
        }

        findViewById<MaterialButton>(R.id.btnCancel).setOnClickListener { finish() }
        findViewById<MaterialButton>(R.id.btnReset).setOnClickListener {
            overlay.resetFull()
            setRotate(0f)
            chips.check(R.id.chipFree)
        }
        findViewById<MaterialButton>(R.id.btnApply).setOnClickListener {
            val c = overlay.crop
            setResult(RESULT_OK, Intent().apply {
                putExtra(EXTRA_U0, c.left)
                putExtra(EXTRA_V0, c.top)
                putExtra(EXTRA_U1, c.right)
                putExtra(EXTRA_V1, c.bottom)
                putExtra(EXTRA_ROT, rotateDeg)
            })
            finish()
        }
    }

    companion object {
        const val EXTRA_U0 = "u0"
        const val EXTRA_V0 = "v0"
        const val EXTRA_U1 = "u1"
        const val EXTRA_V1 = "v1"
        const val EXTRA_ROT = "rot"
    }
}
