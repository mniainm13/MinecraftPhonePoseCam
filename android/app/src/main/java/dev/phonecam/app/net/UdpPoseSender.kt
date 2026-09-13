package dev.phonecam.app.net

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Streams pose JSON packets over UDP to the Minecraft client.
 * Matches protocol/pose-v1.md.
 */
class UdpPoseSender {
    @Volatile var host: String = "127.0.0.1"
    @Volatile var port: Int = 42424

    private var socket: DatagramSocket? = null
    private val running = AtomicBoolean(false)

    fun start() {
        if (running.getAndSet(true)) return
        if (socket == null || socket?.isClosed == true) {
            socket = DatagramSocket()
        }
    }

    fun stop() {
        running.set(false)
        socket?.close()
        socket = null
    }

    fun isRunning(): Boolean = running.get()

    fun send(
        yaw: Float,
        pitch: Float,
        roll: Float,
        posX: Float,
        posY: Float,
        posZ: Float,
        zoom: Float,
        mode: String = "look",
    ) {
        if (!running.get()) return
        val sock = socket ?: return
        val json = buildString {
            append("{\"v\":1,\"t\":")
            append(System.currentTimeMillis())
            append(",\"yaw\":")
            append(fmt(yaw))
            append(",\"pitch\":")
            append(fmt(pitch))
            append(",\"roll\":")
            append(fmt(roll))
            append(",\"pos\":[")
            append(fmtPos(posX)).append(',')
            append(fmtPos(posY)).append(',')
            append(fmtPos(posZ))
            append("],\"zoom\":")
            append(fmt(zoom))
            append(",\"mode\":\"")
            append(mode)
            append("\"}")
        }
        try {
            val data = json.toByteArray(StandardCharsets.UTF_8)
            val address = InetAddress.getByName(host)
            sock.send(DatagramPacket(data, data.size, address, port))
        } catch (_: Exception) {
            // Network flaps are fine; next packet will retry.
        }
    }

    private fun fmt(v: Float): String = String.format(java.util.Locale.US, "%.2f", v)
    /** 0.0001 block — %.2f quantizes camera to 1cm steps and looks stair-steppy when zoomed. */
    private fun fmtPos(v: Float): String = String.format(java.util.Locale.US, "%.4f", v)
}
