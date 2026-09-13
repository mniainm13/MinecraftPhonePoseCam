package dev.phonecam.app.stream

import android.media.MediaCodec
import android.media.MediaCodec.BufferInfo
import android.media.MediaFormat
import android.util.Log
import android.view.Surface
import java.io.DataInputStream
import java.io.EOFException
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Annex-B H.264 → MediaCodec → Surface (scrcpy-style).
 * Feed SPS/PPS in-band; no separate csd (more OEM-friendly).
 */
class H264StreamClient(
    private val host: String,
    private val port: Int,
    private val surface: Surface,
    private val fallbackWidth: Int = 1280,
    private val fallbackHeight: Int = 720,
) {
    companion object {
        private const val TAG = "PhoneCamH264"

        fun startCodeLen(nal: ByteArray): Int {
            if (nal.size >= 4 && nal[0] == 0.toByte() && nal[1] == 0.toByte()
                && nal[2] == 0.toByte() && nal[3] == 1.toByte()
            ) return 4
            if (nal.size >= 3 && nal[0] == 0.toByte() && nal[1] == 0.toByte()
                && nal[2] == 1.toByte()
            ) return 3
            return 0
        }

        fun nalType(nal: ByteArray): Int {
            val i = startCodeLen(nal)
            if (i <= 0 || nal.size <= i) return -1
            return nal[i].toInt() and 0x1F
        }
    }

    private val running = AtomicBoolean(false)
    private var worker: Thread? = null

    @Volatile var status: String = "idle"
        private set

    @Volatile var onStatus: ((String) -> Unit)? = null

    @Volatile var onVideoSize: ((width: Int, height: Int) -> Unit)? = null

    fun start() {
        if (running.getAndSet(true)) return
        worker = thread(name = "phonecam-h264", isDaemon = true) {
            try {
                runLoop()
            } catch (e: Exception) {
                Log.e(TAG, "stream failed", e)
                status = "错误: ${e.message}"
                onStatus?.invoke(status)
            } finally {
                running.set(false)
            }
        }
    }

    fun stop() {
        running.set(false)
        worker?.interrupt()
        worker = null
    }

    private fun runLoop() {
        status = "连接 $host:$port …"
        onStatus?.invoke(status)

        val socket = Socket()
        socket.tcpNoDelay = true
        socket.connect(InetSocketAddress(host, port), 4000)
        val input = DataInputStream(socket.getInputStream().buffered(512 * 1024))

        status = "已连接，等待 SPS…"
        onStatus?.invoke(status)

        var codec: MediaCodec? = null
        var width = fallbackWidth
        var height = fallbackHeight
        var gotSps = false
        var gotPps = false
        var waitKeyframe = true
        var spsBytes: ByteArray? = null
        var ppsBytes: ByteArray? = null

        try {
            while (running.get() && surface.isValid) {
                val nal = readNal(input) ?: break
                if (nal.size < 5) continue
                val type = nalType(nal)
                if (type < 0) continue

                when (type) {
                    7 -> {
                        gotSps = true
                        // Keep Annex-B start code for csd (MediaCodec AVC expects it)
                        spsBytes = nal.copyOf()
                        val dim = parseSpsWidthHeight(nal)
                        if (dim != null) {
                            width = dim.first
                            height = dim.second
                        }
                        status = "SPS ${width}x$height"
                        onStatus?.invoke(status)
                        onVideoSize?.invoke(width, height)
                    }
                    8 -> {
                        gotPps = true
                        ppsBytes = nal.copyOf()
                    }
                    5 -> waitKeyframe = false
                    1 -> if (waitKeyframe) continue
                }

                if (gotSps && gotPps && codec == null) {
                    if (!surface.isValid) {
                        status = "Surface 无效，无法解码"
                        onStatus?.invoke(status)
                    } else {
                        val s = spsBytes
                        val p = ppsBytes
                        if (s != null && p != null) {
                            status = "创建解码器…"
                            onStatus?.invoke(status)
                            try {
                                codec = createCodec(width, height, s, p)
                                activeCodec = codec
                                startDrainLoop()
                                status = "解码中 ${width}x$height"
                                onStatus?.invoke(status)
                            } catch (e: Exception) {
                                Log.e(TAG, "createCodec", e)
                                status = "解码器失败: ${e.javaClass.simpleName}: ${e.message}"
                                onStatus?.invoke(status)
                                gotSps = false
                                gotPps = false
                            }
                        }
                    }
                }

                val c = codec ?: continue
                // Only VCL; keep full Annex-B (start code + NAL)
                if (type != 1 && type != 5) continue
                if (type == 1 && waitKeyframe) continue
                if (!running.get() || !surface.isValid) break

                try {
                    feed(c, nal, type == 5)
                } catch (e: MediaCodec.CodecException) {
                    Log.e(TAG, "CodecException code=${e.errorCode} diag=${e.diagnosticInfo}", e)
                    try { c.stop() } catch (_: Exception) {}
                    try { c.release() } catch (_: Exception) {}
                    activeCodec = null
                    codec = null
                    gotSps = false
                    gotPps = false
                    waitKeyframe = true
                    status = "解码异常: ${e.diagnosticInfo}"
                    onStatus?.invoke(status)
                } catch (e: IllegalStateException) {
                    Log.w(TAG, "codec error", e)
                    try { c.stop() } catch (_: Exception) {}
                    try { c.release() } catch (_: Exception) {}
                    activeCodec = null
                    codec = null
                    gotSps = false
                    gotPps = false
                    waitKeyframe = true
                    status = "等待新 SPS…"
                    onStatus?.invoke(status)
                }
            }
        } catch (_: EOFException) {
            status = "流结束"
            onStatus?.invoke(status)
        } catch (_: IOException) {
            if (running.get()) {
                status = "连接中断"
                onStatus?.invoke(status)
            }
        } finally {
            activeCodec = null
            try { codec?.stop() } catch (_: Exception) {}
            try { codec?.release() } catch (_: Exception) {}
            try { socket.close() } catch (_: Exception) {}
        }
    }

    private fun stripStartCode(nal: ByteArray): ByteArray {
        val i = startCodeLen(nal)
        return if (i > 0) nal.copyOfRange(i, nal.size) else nal
    }

    private fun createCodec(w: Int, h: Int, sps: ByteArray? = null, pps: ByteArray? = null): MediaCodec {
        val ww = w.coerceIn(16, 4096)
        val hh = h.coerceIn(16, 4096)
        val info = pickAvcDecoder()
        val name = info?.name ?: "default"
        Log.i(TAG, "createCodec ${ww}x$hh decoder=$name")

        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, ww, hh)
        // csd = Annex-B SPS/PPS WITH start codes (standard MediaCodec AVC)
        if (sps != null && pps != null) {
            format.setByteBuffer("csd-0", java.nio.ByteBuffer.wrap(sps))
            format.setByteBuffer("csd-1", java.nio.ByteBuffer.wrap(pps))
            Log.i(TAG, "csd0=${sps.size} ${sps.take(6).joinToString(" ") { "%02x".format(it) }} csd1=${pps.size}")
        }
        format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 2 * 1024 * 1024)
        // qti c2 decoder reports COLOR_RANGE_LIMITED and ignores FULL
        try {
            format.setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_LIMITED)
        } catch (_: Exception) {}
        try {
            format.setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT709)
        } catch (_: Exception) {}
        try {
            format.setInteger(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_SDR_VIDEO)
        } catch (_: Exception) {}
        try {
            format.setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
        } catch (_: Exception) {}
        try {
            format.setInteger(MediaFormat.KEY_PRIORITY, 0)
        } catch (_: Exception) {}

        val codec = if (info != null) {
            MediaCodec.createByCodecName(info.name)
        } else {
            MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        }
        codec.configure(format, surface, null, 0)
        codec.start()
        try {
            val of = codec.outputFormat
            val range = of.getInteger(MediaFormat.KEY_COLOR_RANGE, -1)
            val std = of.getInteger(MediaFormat.KEY_COLOR_STANDARD, -1)
            Log.i(TAG, "decoder started name=$name colorRange=$range standard=$std")
        } catch (e: Exception) {
            Log.i(TAG, "decoder started $name")
        }
        return codec
    }

    /** Prefer hardware AVC decoders (Moonlight: skip *sw*, prefer FEATURE_LowLatency). */
    private fun pickAvcDecoder(): android.media.MediaCodecInfo? {
        return try {
            val list = android.media.MediaCodecList(android.media.MediaCodecList.REGULAR_CODECS)
            var best: android.media.MediaCodecInfo? = null
            var bestScore = -1
            for (info in list.codecInfos) {
                if (!info.isEncoder) {
                    val types = info.supportedTypes
                    if (types.any { it.equals(MediaFormat.MIMETYPE_VIDEO_AVC, true) }) {
                        val n = info.name.lowercase()
                        if (n.contains("sw") || n.contains("google")) continue
                        var score = 1
                        if (n.contains("qti") || n.contains("qcom")) score += 3
                        // Prefer plain hardware over .low_latency (errata: LL can break on some OEM builds)
                        if (n.contains("low_latency")) score -= 2
                        if (score > bestScore) {
                            bestScore = score
                            best = info
                        }
                    }
                }
            }
            best
        } catch (e: Exception) {
            Log.w(TAG, "pickAvcDecoder", e)
            null
        }
    }

    private var ptsUs = 0L
    private val frameDurationUs = 16_666L
    @Volatile private var activeCodec: MediaCodec? = null

    private fun startDrainLoop() {
        thread(name = "phonecam-drain", isDaemon = true) {
            val info = BufferInfo()
            while (running.get()) {
                val c = activeCodec ?: break
                try {
                    val out = c.dequeueOutputBuffer(info, 10_000)
                    when {
                        out >= 0 -> {
                            val render = info.size > 0 && surface.isValid
                            c.releaseOutputBuffer(out, render)
                            if (render) {
                                renderCount++
                                if (renderCount == 1 || renderCount % 30 == 0) {
                                    status = "解码中 render=$renderCount"
                                    onStatus?.invoke(status)
                                    Log.i(TAG, "render=$renderCount")
                                }
                            }
                        }
                        out == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            Log.i(TAG, "outputFormat=${c.outputFormat}")
                        }
                    }
                } catch (_: IllegalStateException) {
                    break
                } catch (_: Exception) {
                    break
                }
            }
        }
    }

    private fun feed(codec: MediaCodec, nal: ByteArray, isIdr: Boolean) {
        if (!running.get() || !surface.isValid) return
        val inIndex = codec.dequeueInputBuffer(5_000)
        if (inIndex < 0) {
            return
        }
        val buf = codec.getInputBuffer(inIndex) ?: return
        buf.clear()
        // Annex-B: keep start code + NAL (MediaCodec expects this for raw H.264)
        if (nal.size > buf.capacity()) {
            codec.queueInputBuffer(inIndex, 0, 0, 0, 0)
            return
        }
        buf.put(nal, 0, nal.size)
        ptsUs += frameDurationUs
        val flags = if (isIdr) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
        codec.queueInputBuffer(inIndex, 0, nal.size, ptsUs, flags)
        if (isIdr) {
            Log.i(TAG, "queue IDR size=${nal.size} head=${nal.take(8).joinToString(" ") { "%02x".format(it) }}")
        }
    }

    private var renderCount = 0

    private fun drain(codec: MediaCodec) {
        val info = BufferInfo()
        var loops = 0
        while (running.get() && loops++ < 64) {
            val out = try {
                // small wait so output can emerge (0 often yields nothing)
                codec.dequeueOutputBuffer(info, 5_000)
            } catch (_: IllegalStateException) {
                break
            }
            when {
                out >= 0 -> {
                    val render = info.size > 0 && surface.isValid
                    try {
                        codec.releaseOutputBuffer(out, render)
                        if (render) {
                            renderCount++
                            if (renderCount == 1 || renderCount % 60 == 0) {
                                status = "解码中 render=$renderCount"
                                onStatus?.invoke(status)
                            }
                        }
                    } catch (_: IllegalStateException) {
                        break
                    }
                }
                out == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val f = try { codec.outputFormat } catch (_: Exception) { null }
                    Log.i(TAG, "output format $f")
                }
                out == MediaCodec.INFO_TRY_AGAIN_LATER -> break
                else -> break
            }
        }
    }

    private var pendingStartPrefix: ByteArray? = null

    private fun readNal(input: DataInputStream): ByteArray? {
        val prefix = pendingStartPrefix
        pendingStartPrefix = null
        if (prefix != null) return readNalBody(input, prefix)

        var zeroCount = 0
        while (running.get()) {
            val b = input.read()
            if (b < 0) return null
            if (b == 0) zeroCount++
            else if (b == 1 && zeroCount >= 2) break
            else zeroCount = 0
        }
        val zeros = zeroCount.coerceIn(2, 3)
        val sc = ByteArray(zeros + 1)
        for (i in 0 until zeros) sc[i] = 0
        sc[zeros] = 1
        return readNalBody(input, sc)
    }

    private fun readNalBody(input: DataInputStream, startCode: ByteArray): ByteArray? {
        val body = ArrayList<Byte>(startCode.size + 32768)
        for (b in startCode) body.add(b)

        var zeroCount = 0
        while (running.get()) {
            val b = input.read()
            if (b < 0) {
                return if (body.size > startCode.size + 1) body.toByteArray() else null
            }
            if (b == 0) {
                zeroCount++
                continue
            }
            if (b == 1 && zeroCount >= 2) {
                val used = zeroCount.coerceIn(2, 3)
                val sc = ByteArray(used + 1)
                for (i in 0 until used) sc[i] = 0
                sc[used] = 1
                pendingStartPrefix = sc
                return body.toByteArray().takeIf { it.size > startCode.size + 1 }
            }
            repeat(zeroCount) { body.add(0) }
            zeroCount = 0
            body.add(b.toByte())
            if (body.size > 2_000_000) return null
        }
        return null
    }

    private fun parseSpsWidthHeight(nal: ByteArray): Pair<Int, Int>? {
        return try {
            val i = startCodeLen(nal)
            if (i <= 0) return null
            val rbsp = nal.copyOfRange(i + 1, nal.size)
            val bits = BitReader(rbsp)
            val profile = rbsp[0].toInt() and 0xFF
            bits.skip(8) // profile
            bits.skip(8)
            bits.skip(8)
            bits.ue()
            if (profile == 100 || profile == 110 || profile == 122 || profile == 244 ||
                profile == 44 || profile == 83 || profile == 86 || profile == 118 ||
                profile == 128 || profile == 138 || profile == 139 || profile == 134 ||
                profile == 135
            ) {
                val chroma = bits.ue()
                if (chroma == 3) bits.skip(1)
                bits.ue(); bits.ue()
                bits.skip(1)
                if (bits.readBit()) {
                    val n = if (chroma != 3) 8 else 12
                    repeat(n) {
                        if (bits.readBit()) {
                            var last = 8
                            var next = 8
                            val size = if (it < 6) 16 else 64
                            repeat(size) {
                                if (next != 0) {
                                    val delta = bits.se()
                                    next = (last + delta + 256) % 256
                                }
                                if (next != 0) last = next
                            }
                        }
                    }
                }
            }
            bits.ue()
            val pocType = bits.ue()
            if (pocType == 0) bits.ue()
            else if (pocType == 1) {
                bits.skip(1)
                bits.se(); bits.se()
                val n = bits.ue()
                repeat(n) { bits.se() }
            }
            bits.ue()
            bits.skip(1)
            val wMbs = bits.ue() + 1
            val hMap = bits.ue() + 1
            val frameMbsOnly = bits.readBit()
            if (!frameMbsOnly) bits.skip(1)
            bits.skip(1)
            val cropL = if (bits.readBit()) bits.ue() else 0
            val cropR = if (bits.readBit()) bits.ue() else 0
            val cropT = if (bits.readBit()) bits.ue() else 0
            val cropB = if (bits.readBit()) bits.ue() else 0
            val w = wMbs * 16 - (cropL + cropR)
            val h = hMap * 16 - (cropT + cropB) * (if (frameMbsOnly) 1 else 2)
            if (w in 16..4096 && h in 16..4096) w to h else null
        } catch (_: Exception) {
            null
        }
    }

    private class BitReader(private val data: ByteArray) {
        private var bitPos = 0

        fun readBit(): Boolean {
            if (bitPos / 8 >= data.size) return false
            val byte = data[bitPos / 8].toInt() and 0xFF
            val bit = (byte shr (7 - (bitPos % 8))) and 1
            bitPos++
            return bit == 1
        }

        fun skip(n: Int) {
            repeat(n) { readBit() }
        }

        fun ue(): Int {
            var zeros = 0
            while (!readBit() && zeros < 32) zeros++
            var info = 0
            repeat(zeros) { info = (info shl 1) or (if (readBit()) 1 else 0) }
            return (1 shl zeros) - 1 + info
        }

        fun se(): Int {
            val v = ue()
            return if (v and 1 == 1) (v + 1) / 2 else -(v / 2)
        }
    }
}
