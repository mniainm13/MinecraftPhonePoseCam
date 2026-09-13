package dev.phonecam.app.sensor

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * Phone pose → Minecraft look-space relative yaw/pitch/roll.
 *
 * Pipeline (see docs/pose-normalization.md):
 *  1. TYPE_GAME_ROTATION_VECTOR (device → Android world, Z-up)
 *  2. Convert to game look space (Y-up) via fixed rotation
 *  3. Relative quaternion vs calibration: qRel = conj(q0) ⊗ qNow
 *  4. Euler YXZ → yaw / pitch / roll (degrees)
 *  5. Gain + deadzone + light 1€-style smoothing
 */
class OrientationFusion(context: Context) : SensorEventListener {

    fun interface Callback {
        fun onOrientation(yaw: Float, pitch: Float, roll: Float)
    }

    private val sensorManager =
        context.getSystemService(Context.SENSOR_SERVICE) as SensorManager

    private val gameRv: Sensor? =
        sensorManager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
    private val rotationVector: Sensor? =
        sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
    private val fallbackSensor: Sensor? = gameRv ?: rotationVector

    private var callback: Callback? = null

    // Current orientation as quaternion [w,x,y,z] in GAME look space (Y-up)
    private val qNow = FloatArray(4) { if (it == 0) 1f else 0f }
    private val qCal = FloatArray(4) { if (it == 0) 1f else 0f }
    private var calibrated = false
    private var hasSample = false

    // Output state
    private var yawDeg = 0f
    private var pitchDeg = 0f
    private var rollDeg = 0f

    // 1€ filter state per channel
    private class OneEuro(
        var minCutoff: Float = 1.0f,
        var beta: Float = 0.02f,
        var dCutoff: Float = 1.0f,
    ) {
        private var xPrev = 0f
        private var dxPrev = 0f
        private var init = false

        private fun alpha(cutoff: Float, dt: Float): Float {
            val tau = 1f / (2f * Math.PI.toFloat() * cutoff)
            return 1f / (1f + tau / dt)
        }

        fun filter(value: Float, dt: Float): Float {
            if (dt <= 0f || dt > 0.25f) return value
            if (!init) {
                xPrev = value
                dxPrev = 0f
                init = true
                return value
            }
            val dx = (value - xPrev) / dt
            val edx = alpha(dCutoff, dt) * dx + (1 - alpha(dCutoff, dt)) * dxPrev
            val cutoff = minCutoff + beta * abs(edx)
            val x = alpha(cutoff, dt) * value + (1 - alpha(cutoff, dt)) * xPrev
            xPrev = x
            dxPrev = edx
            return x
        }

        fun reset() {
            init = false
            xPrev = 0f
            dxPrev = 0f
        }
    }

    private val yawFilter = OneEuro(minCutoff = 0.8f, beta = 0.05f)
    private val pitchFilter = OneEuro(minCutoff = 0.8f, beta = 0.04f)
    private val rollFilter = OneEuro(minCutoff = 0.6f, beta = 0.03f)
    private var lastNs = 0L

    /** Degrees of motion ignored (anti-jitter). */
    @Volatile var deadzoneDeg = 0.8f
    @Volatile var sensitivity = 1.0f
    @Volatile var invertY = false
    @Volatile var invertX = false

    fun isSensorAvailable(): Boolean = fallbackSensor != null

    fun sensorName(): String = when (fallbackSensor) {
        null -> "none"
        gameRv -> "GAME_ROTATION_VECTOR"
        else -> "ROTATION_VECTOR"
    }

    fun start(callback: Callback) {
        this.callback = callback
        hasSample = false
        lastNs = 0L
        yawFilter.reset()
        pitchFilter.reset()
        rollFilter.reset()
        val sensor = fallbackSensor
        if (sensor != null) {
            sensorManager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_GAME)
        }
    }

    fun stop() {
        sensorManager.unregisterListener(this)
        callback = null
    }

    fun calibrate() {
        System.arraycopy(qNow, 0, qCal, 0, 4)
        calibrated = true
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_GAME_ROTATION_VECTOR
            && event.sensor.type != Sensor.TYPE_ROTATION_VECTOR
        ) {
            return
        }
        // Android: [x,y,z,(cos(theta/2)),optional accuracy]
        val values = event.values
        if (values.size < 3) return

        // Quaternion from rotation vector: w,x,y,z
        val qw: Float
        val qx: Float
        val qy: Float
        val qz: Float
        if (values.size >= 4) {
            // Prefer getQuaternionFromVector when available shape
            val tmp = FloatArray(4)
            SensorManager.getQuaternionFromVector(tmp, values)
            // tmp is [w,x,y,z]
            qw = tmp[0]
            qx = tmp[1]
            qy = tmp[2]
            qz = tmp[3]
        } else {
            val x = values[0]
            val y = values[1]
            val z = values[2]
            val w = sqrt((1f - x * x - y * y - z * z).coerceAtLeast(0f))
            qw = w
            qx = x
            qy = y
            qz = z
        }

        // Android world: X east, Y north, Z up.
        // Game look: X right, Y up, Z forward (into scene).
        // Convert: rotate -90° about X so Z_up → Y_up.
        // q_fix = q_rotX(-90°) ⊗ q_android  (left-multiply fixed world rotation)
        val half = Math.PI.toFloat() / 4f
        val cx = kotlin.math.cos(-half)
        val sx = kotlin.math.sin(-half)
        // qFix = [w=cx, x=sx, y=0, z=0]
        val gw = cx * qw - sx * qx
        val gx = cx * qx + sx * qw
        val gy = cx * qy - sx * qz
        val gz = cx * qz + sx * qy

        qNow[0] = gw
        qNow[1] = gx
        qNow[2] = gy
        qNow[3] = gz
        normalize(qNow)

        if (!calibrated) {
            System.arraycopy(qNow, 0, qCal, 0, 4)
            calibrated = true
        }

        // qRel = conj(qCal) ⊗ qNow
        val rel = conjMul(qCal, qNow)
        normalize(rel)

        // Euler YXZ (yaw Y, pitch X, roll Z) in degrees
        // Sign flip: device→game frame still mirrored on this path; invert all three.
        val (yRaw, pRaw, rRaw) = quatToEulerYXZ(rel)
        val y = -yRaw
        val p = -pRaw
        val r = -rRaw

        var outYaw = y * sensitivity
        var outPitch = p * sensitivity
        var outRoll = r * sensitivity
        if (invertX) outYaw = -outYaw
        if (invertY) outPitch = -outPitch

        outYaw = applyDeadzone(outYaw, deadzoneDeg)
        outPitch = applyDeadzone(outPitch, deadzoneDeg)
        outRoll = applyDeadzone(outRoll, deadzoneDeg)

        if (outPitch > 90f) outPitch = 90f
        if (outPitch < -90f) outPitch = -90f
        if (outRoll > 90f) outRoll = 90f
        if (outRoll < -90f) outRoll = -90f

        val dt = if (lastNs == 0L) 0.016f
        else (event.timestamp - lastNs) * 1e-9f
        lastNs = event.timestamp

        yawDeg = yawFilter.filter(outYaw, dt)
        pitchDeg = pitchFilter.filter(outPitch, dt)
        rollDeg = rollFilter.filter(outRoll, dt)
        hasSample = true

        callback?.onOrientation(yawDeg, pitchDeg, rollDeg)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private fun applyDeadzone(v: Float, dz: Float): Float {
        if (abs(v) < dz) return 0f
        val sign = if (v >= 0) 1f else -1f
        return sign * (abs(v) - dz)
    }

    private fun normalize(q: FloatArray) {
        val n = sqrt(q[0] * q[0] + q[1] * q[1] + q[2] * q[2] + q[3] * q[3])
        if (n < 1e-8f) {
            q[0] = 1f
            q[1] = 0f
            q[2] = 0f
            q[3] = 0f
            return
        }
        q[0] /= n
        q[1] /= n
        q[2] /= n
        q[3] /= n
    }

    /** result = conj(a) ⊗ b, both [w,x,y,z]. */
    private fun conjMul(a: FloatArray, b: FloatArray): FloatArray {
        val aw = a[0]
        val ax = -a[1]
        val ay = -a[2]
        val az = -a[3]
        val bw = b[0]
        val bx = b[1]
        val by = b[2]
        val bz = b[3]
        return floatArrayOf(
            aw * bw - ax * bx - ay * by - az * bz,
            aw * bx + ax * bw + ay * bz - az * by,
            aw * by - ax * bz + ay * bw + az * bx,
            aw * bz + ax * by - ay * bx + az * bw,
        )
    }

    /**
     * Extract YXZ Euler angles (radians) from unit quaternion.
     * Matches Minecraft Camera.rotationYXZ(yaw, pitch, roll).
     */
    private fun quatToEulerYXZ(q: FloatArray): Triple<Float, Float, Float> {
        val w = q[0]
        val x = q[1]
        val y = q[2]
        val z = q[3]

        // Rotation matrix elements (row-major 3x3), JOML / MC convention
        val m00 = 1f - 2f * (y * y + z * z)
        val m01 = 2f * (x * y - z * w)
        val m02 = 2f * (x * z + y * w)
        val m10 = 2f * (x * y + z * w)
        val m11 = 1f - 2f * (x * x + z * z)
        val m12 = 2f * (y * z - x * w)
        val m20 = 2f * (x * z - y * w)
        val m21 = 2f * (y * z + x * w)
        val m22 = 1f - 2f * (x * x + y * y)

        // YXZ: pitch = asin(-m12) or similar depending on convention
        // JOML Quaternionf.rotationYXZ(yaw, pitch, roll) uses:
        //   R = Ry(yaw) * Rx(pitch) * Rz(roll)
        val pitch = asin((-m12).coerceIn(-1f, 1f))
        val yaw: Float
        val roll: Float
        if (abs(m12) < 0.9999f) {
            yaw = atan2(m02, m22)
            roll = atan2(m10, m11)
        } else {
            // Gimbal lock
            yaw = atan2(-m20, m00)
            roll = 0f
        }
        return Triple(
            Math.toDegrees(yaw.toDouble()).toFloat(),
            Math.toDegrees(pitch.toDouble()).toFloat(),
            Math.toDegrees(roll.toDouble()).toFloat(),
        )
    }
}
