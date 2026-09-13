package dev.phonecam.app.sensor

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlin.math.sqrt

/**
 * 6DoF position (no ARCore): TYPE_LINEAR_ACCELERATION + GAME_ROTATION_VECTOR.
 * Integrates world-frame acceleration with rest detection (ZUPT-like).
 * Drifts over minutes — calibrate/reset often. Scale maps meters → MC blocks.
 */
class PositionTracker(context: Context) : SensorEventListener {

    private val sensorManager =
        context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val linAccel: Sensor? =
        sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
    private val gameRv: Sensor? =
        sensorManager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)

    private val rotMatrix = FloatArray(9)
    private var hasRot = false

    // World position in meters (Y-up after remap: X right, Y up, Z forward-ish)
    @Volatile var posX = 0f
    @Volatile var posY = 0f
    @Volatile var posZ = 0f

    private var velX = 0f
    private var velY = 0f
    private var velZ = 0f

    private var lastNs = 0L
    private var restAccumNs = 0L
    private var lastAccelMag = 0f

    /** Meters of phone travel → 1 Minecraft block. */
    @Volatile var metersPerBlock = 0.25f
    @Volatile var enabled = true

    fun isAvailable(): Boolean = linAccel != null && gameRv != null

    fun start() {
        gameRv?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
        linAccel?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
    }

    fun stop() {
        sensorManager.unregisterListener(this)
    }

    fun reset() {
        posX = 0f; posY = 0f; posZ = 0f
        velX = 0f; velY = 0f; velZ = 0f
        lastNs = 0L
    }

    /** Call when phone is held still (after calibrate). */
    fun zeroVelocity() {
        velX = 0f; velY = 0f; velZ = 0f
    }

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_GAME_ROTATION_VECTOR -> {
                SensorManager.getRotationMatrixFromVector(rotMatrix, event.values)
                hasRot = true
            }
            Sensor.TYPE_LINEAR_ACCELERATION -> {
                if (!enabled || !hasRot) return
                val dt = if (lastNs == 0L) 0.016f
                else (event.timestamp - lastNs) * 1e-9f
                lastNs = event.timestamp
                if (dt <= 0f || dt > 0.05f) return

                // Device linear accel (m/s^2), Android axes
                val ax = event.values[0]
                val ay = event.values[1]
                val az = event.values[2]
                val mag = sqrt(ax * ax + ay * ay + az * az)
                lastAccelMag = mag

                // Rest: very small accel for >200ms → zero velocity (kill drift)
                if (mag < 0.15f) {
                    restAccumNs += (dt * 1e9).toLong()
                    if (restAccumNs > 200_000_000L) {
                        velX = 0f; velY = 0f; velZ = 0f
                    }
                } else {
                    restAccumNs = 0
                }

                // Rotate device accel into Android world (Z-up)
                // R * a
                val wx = rotMatrix[0] * ax + rotMatrix[1] * ay + rotMatrix[2] * az
                val wy = rotMatrix[3] * ax + rotMatrix[4] * ay + rotMatrix[5] * az
                val wz = rotMatrix[6] * ax + rotMatrix[7] * ay + rotMatrix[8] * az

                // Android world: X east, Y north, Z up → game: X right, Y up, Z forward
                // Map: gameX = wx, gameY = wz, gameZ = -wy  (rough portrait mapping)
                val gx = wx
                val gy = wz
                val gz = -wy

                velX += gx * dt
                velY += gy * dt
                velZ += gz * dt

                posX += velX * dt
                posY += velY * dt
                posZ += velZ * dt

                // Soft clamp so a bad spike doesn't send you to space
                val lim = 8f
                if (posX > lim) posX = lim
                if (posX < -lim) posX = -lim
                if (posY > lim) posY = lim
                if (posY < -lim) posY = -lim
                if (posZ > lim) posZ = lim
                if (posZ < -lim) posZ = -lim
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    /** Position in Minecraft blocks for the pose packet. */
    fun blockX(): Float = posX / metersPerBlock
    fun blockY(): Float = posY / metersPerBlock
    fun blockZ(): Float = posZ / metersPerBlock
}
