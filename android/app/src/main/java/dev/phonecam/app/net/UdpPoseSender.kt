package dev.phonecam.app.net

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * Streams pose JSON packets over UDP to the Minecraft client.
 * Matches protocol/pose-v1.md.
 */
class UdpPoseSender {
    @Volatile var host: String = "127.0.0.1"
        set(value) {
            field = value
            cachedAddr = null
        }
    @Volatile var port: Int = 42424

    private var socket: DatagramSocket? = null
    private val running = AtomicBoolean(false)
    private var cachedAddr: InetAddress? = null

    val sentCount = AtomicInteger(0)
    val errorCount = AtomicInteger(0)
    val lastError = AtomicReference<String?>(null)

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
        cachedAddr = null
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
        val addr = resolveHost() ?: return
        val json = buildString {
            append("{\"v\":1,\"t\":")
            append(System.currentTimeMillis())
            append(",\"yaw\":")
            append(fmt2(yaw))
            append(",\"pitch\":")
            append(fmt2(pitch))
            append(",\"roll\":")
            append(fmt2(roll))
            append(",\"pos\":[")
            append(fmt4(posX)).append(',')
            append(fmt4(posY)).append(',')
            append(fmt4(posZ))
            append("],\"zoom\":")
            append(fmt2(zoom))
            append(",\"mode\":\"")
            append(mode)
            append("\"}")
        }
        try {
            val data = json.toByteArray(StandardCharsets.UTF_8)
            sock.send(DatagramPacket(data, data.size, addr, port))
            sentCount.incrementAndGet()
        } catch (e: Exception) {
            errorCount.incrementAndGet()
            lastError.set(e.message)
        }
    }

    private fun resolveHost(): InetAddress? {
        cachedAddr?.let { return it }
        return try {
            val a = InetAddress.getByName(host)
            cachedAddr = a
            a
        } catch (e: Exception) {
            errorCount.incrementAndGet()
            lastError.set(e.message)
            null
        }
    }

    /** %.2f without String.format on the 60Hz path. */
    private fun fmt2(v: Float): String {
        if (v.isNaN() || v.isInfinite()) return "0.00"
        val x = (v * 100f).toLong()
        val neg = x < 0L
        val ax = if (neg) -x else x
        val whole = ax / 100
        val frac = ax % 100
        return buildString(12) {
            if (neg) append('-')
            append(whole).append('.')
            if (frac < 10) append('0')
            append(frac)
        }
    }

    /** %.4f — 0.0001 block so camera does not stair-step when zoomed. */
    private fun fmt4(v: Float): String {
        if (v.isNaN() || v.isInfinite()) return "0.0000"
        val x = (v * 10000f).toLong()
        val neg = x < 0L
        val ax = if (neg) -x else x
        val whole = ax / 10000
        val frac = ax % 10000
        return buildString(16) {
            if (neg) append('-')
            append(whole).append('.')
            var f = frac
            repeat(4 - f.toString().length) { append('0') }
            append(f)
        }
    }
}
