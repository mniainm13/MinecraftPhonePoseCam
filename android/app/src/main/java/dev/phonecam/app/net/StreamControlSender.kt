package dev.phonecam.app.net

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

/** Sends stream-settings JSON to Stream Host control UDP (dataPort+1, default 8092). */
class StreamControlSender {
    private var socket: DatagramSocket? = null

    fun send(host: String, dataPort: Int, bitrateMbps: Int, fps: Int) {
        try {
            val sock = socket ?: DatagramSocket().also { socket = it }
            val json = """{"bitrate":$bitrateMbps,"fps":$fps}"""
            val data = json.toByteArray(Charsets.UTF_8)
            sock.send(DatagramPacket(data, data.size, InetAddress.getByName(host), dataPort + 1))
        } catch (_: Exception) {
        }
    }

    fun close() {
        socket?.close()
        socket = null
    }
}
