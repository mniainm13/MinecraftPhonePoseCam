package dev.phonecam.app.stream

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Raw PCM from PC WASAPI loopback: 48kHz stereo s16le over TCP :8093.
 */
class AudioStreamClient(
    private val host: String,
    private val port: Int = 8093,
) {
    companion object {
        private const val TAG = "PhoneCamAudio"
        private const val RATE = 48000
        private const val CH = 2
    }

    private val running = AtomicBoolean(false)
    private var worker: Thread? = null
    private var track: AudioTrack? = null

    @Volatile var onStatus: ((String) -> Unit)? = null

    fun start() {
        if (running.getAndSet(true)) return
        val minBuf = AudioTrack.getMinBufferSize(
            RATE,
            AudioFormat.CHANNEL_OUT_STEREO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(RATE)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                    .build()
            )
            .setBufferSizeInBytes(minBuf * 2)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        track?.play()
        worker = thread(name = "phonecam-audio", isDaemon = true) {
            try {
                runLoop()
            } catch (e: Exception) {
                Log.e(TAG, "audio", e)
                onStatus?.invoke("音频错误: ${e.message}")
            } finally {
                running.set(false)
                try { track?.stop() } catch (_: Exception) {}
                try { track?.release() } catch (_: Exception) {}
                track = null
            }
        }
    }

    fun stop() {
        running.set(false)
        worker?.interrupt()
        worker = null
    }

    private fun runLoop() {
        onStatus?.invoke("音频连接 $host:$port")
        while (running.get()) {
            try {
                val socket = Socket()
                socket.tcpNoDelay = true
                socket.connect(InetSocketAddress(host, port), 4000)
                val input: InputStream = socket.getInputStream().buffered(64 * 1024)
                onStatus?.invoke("音频已连接")
                val buf = ByteArray(4096)
                while (running.get()) {
                    val n = input.read(buf)
                    if (n <= 0) break
                    track?.write(buf, 0, n)
                }
                try { socket.close() } catch (_: Exception) {}
            } catch (e: Exception) {
                if (!running.get()) break
                Log.w(TAG, "audio reconnect: ${e.message}")
                onStatus?.invoke("音频重连中…")
            }
            var wait = 0
            while (running.get() && wait < 2000) {
                try { Thread.sleep(100) } catch (_: InterruptedException) { break }
                wait += 100
            }
        }
        onStatus?.invoke("音频断开")
    }
}
