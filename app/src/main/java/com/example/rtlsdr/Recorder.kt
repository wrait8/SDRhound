package com.example.rtlsdr

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.util.concurrent.Executors

/**
 * Records demodulated audio (16-bit mono 48 kHz .wav) and/or raw IQ (.cu8 = unsigned 8-bit I/Q pairs,
 * the RTL-SDR native format, opens in Inspectrum, URH, GQRX, SDR++ ...).
 * Files go to Downloads/RtlSdr (Android 10+) or the app's own folder on older phones.
 * IQ is ~3.8 MB per second (about 230 MB per minute).
 */
class Recorder(private val ctx: Context, private val onMessage: (String) -> Unit) {
    private class Sink(val ch: FileChannel, val pfd: ParcelFileDescriptor?, val label: String) { var bytes = 0L }

    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    var audioOn by mutableStateOf(false); private set
    var iqOn by mutableStateOf(false); private set
    var pcapOn by mutableStateOf(false); private set
    @Volatile var audioSince = 0L; private set
    @Volatile var iqSince = 0L; private set
    @Volatile var pcapSince = 0L; private set
    @Volatile private var audio: Sink? = null
    @Volatile private var iq: Sink? = null
    @Volatile private var pcap: Sink? = null

    private fun open(name: String, mime: String): Sink? = try {
        if (Build.VERSION.SDK_INT >= 29) {
            val cv = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/RtlSdr")
            }
            val uri = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv)
            val pfd = uri?.let { ctx.contentResolver.openFileDescriptor(it, "rw") }
            if (pfd == null) null else Sink(FileOutputStream(pfd.fileDescriptor).channel, pfd, "Downloads/RtlSdr/$name")
        } else {
            val dir = File(ctx.getExternalFilesDir(null), "recordings").apply { mkdirs() }
            val f = File(dir, name)
            Sink(FileOutputStream(f).channel, null, f.absolutePath)
        }
    } catch (e: Exception) { null }

    private fun say(msg: String) { main.post { onMessage(msg) } }

    fun startAudio(name: String) {
        if (audioOn) return
        val s = open(name, "audio/wav") ?: return say("can't create file")
        s.ch.write(wavHeader(0))
        audio = s; audioSince = System.currentTimeMillis(); audioOn = true
    }

    fun startIq(name: String) {
        if (iqOn) return
        val s = open(name, "application/octet-stream") ?: return say("can't create file")
        iq = s; iqSince = System.currentTimeMillis(); iqOn = true
    }

    fun stopAudio() {
        val s = audio ?: return
        audio = null; audioOn = false
        io.execute {
            try {
                val b = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
                b.putInt((36 + s.bytes).toInt()).flip(); s.ch.write(b, 4)
                val d = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
                d.putInt(s.bytes.toInt()).flip(); s.ch.write(d, 40)
            } catch (_: Exception) {}
            close(s)
        }
    }

    fun stopIq() {
        val s = iq ?: return
        iq = null; iqOn = false
        io.execute { close(s) }
    }

    fun stopAll() { stopAudio(); stopIq(); stopPcap() }

    /** .pcap of decoded GSM control-channel messages, readable directly in Wireshark: each GSMTAP payload
        is wrapped in a fake Ethernet/IPv4/UDP frame to port 4729 so Wireshark auto-picks its GSMTAP
        dissector, exactly as if it had captured the live UDP stream (see [startVerify]/[Gsmtap]). */
    fun startPcap(name: String) {
        if (pcapOn) return
        val s = open(name, "application/vnd.tcpdump.pcap") ?: return say("can't create file")
        try { s.ch.write(pcapGlobalHeader()) } catch (_: Exception) {}
        pcap = s; pcapSince = System.currentTimeMillis(); pcapOn = true
    }

    fun stopPcap() {
        val s = pcap ?: return
        pcap = null; pcapOn = false
        io.execute { close(s) }
    }

    /** Called from the GSM decode thread with one GSMTAP payload (header + L3 message). */
    fun writePcapPacket(gsmtapFrame: ByteArray) {
        val s = pcap ?: return
        val frame = ethIpUdpWrap(gsmtapFrame)
        val nowUs = System.currentTimeMillis() * 1000L
        val rec = ByteBuffer.allocate(16 + frame.size).order(ByteOrder.LITTLE_ENDIAN)
        rec.putInt((nowUs / 1_000_000L).toInt())
        rec.putInt((nowUs % 1_000_000L).toInt())
        rec.putInt(frame.size)
        rec.putInt(frame.size)
        rec.put(frame)
        rec.flip()
        io.execute { try { while (rec.hasRemaining()) s.ch.write(rec); s.bytes += rec.capacity() } catch (_: Exception) {} }
    }

    private fun pcapGlobalHeader(): ByteBuffer {
        val b = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN)
        b.putInt(0xa1b2c3d4.toInt()) // magic (native byte order, microsecond resolution)
        b.putShort(2); b.putShort(4) // version 2.4
        b.putInt(0)                  // thiszone
        b.putInt(0)                  // sigfigs
        b.putInt(65535)              // snaplen
        b.putInt(1)                  // linktype = LINKTYPE_ETHERNET
        b.flip()
        return b
    }

    /** Dummy Ethernet + IPv4 + UDP (dst port 4729) wrapper around a GSMTAP payload. Only the IPv4 header
        checksum is filled in (UDP checksum 0 = not used, which is valid for IPv4); source/dest addresses
        and MACs are placeholders since only the GSMTAP payload matters for dissection. */
    private fun ethIpUdpWrap(payload: ByteArray): ByteArray {
        val udpLen = 8 + payload.size
        val ipLen = 20 + udpLen
        val buf = ByteBuffer.allocate(14 + ipLen).order(ByteOrder.BIG_ENDIAN)
        // Ethernet: dst, src (locally-administered dummy MACs), ethertype IPv4
        buf.put(byteArrayOf(0x02, 0, 0, 0, 0, 1))
        buf.put(byteArrayOf(0x02, 0, 0, 0, 0, 2))
        buf.putShort(0x0800)
        // IPv4
        val ipStart = buf.position()
        buf.put(0x45); buf.put(0)              // version/IHL, DSCP/ECN
        buf.putShort(ipLen.toShort())
        buf.putShort(0); buf.putShort(0)       // id, flags/fragoff
        buf.put(64); buf.put(17)               // TTL, protocol = UDP
        buf.putShort(0)                        // checksum placeholder
        buf.put(byteArrayOf(127, 0, 0, 1))     // src 127.0.0.1
        buf.put(byteArrayOf(127, 0, 0, 1))     // dst 127.0.0.1
        // UDP
        buf.putShort(47290.toShort())          // src port (arbitrary)
        buf.putShort(4729)                     // dst port = GSMTAP
        buf.putShort(udpLen.toShort())
        buf.putShort(0)                        // checksum (0 = unused, valid for IPv4)
        buf.put(payload)

        val arr = buf.array()
        var sum = 0
        var p = ipStart
        val ipEnd = ipStart + 20
        while (p < ipEnd) { sum += (((arr[p].toInt() and 0xFF) shl 8) or (arr[p + 1].toInt() and 0xFF)); p += 2 }
        while (sum shr 16 != 0) sum = (sum and 0xFFFF) + (sum shr 16)
        val csum = sum.inv() and 0xFFFF
        arr[ipStart + 10] = (csum shr 8).toByte(); arr[ipStart + 11] = csum.toByte()
        return arr
    }

    private fun close(s: Sink) {
        try { s.ch.close(); s.pfd?.close() } catch (_: Exception) {}
        say("saved %s (%.1f MB)".format(s.label, s.bytes / 1e6))
    }

    /** Called from the radio thread with every raw IQ chunk. */
    fun writeIq(raw: ByteArray, len: Int) {
        val s = iq ?: return
        val bb = ByteBuffer.wrap(raw.copyOf(len))
        io.execute { try { while (bb.hasRemaining()) s.ch.write(bb); s.bytes += len } catch (_: Exception) {} }
    }

    /** Called from the radio thread with 48 kHz float audio. */
    fun writeAudio(a: FloatArray, n: Int) {
        val s = audio ?: return
        val bb = ByteBuffer.allocate(n * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (k in 0 until n) bb.putShort((a[k].coerceIn(-1f, 1f) * 32767f).toInt().toShort())
        bb.flip()
        io.execute { try { while (bb.hasRemaining()) s.ch.write(bb); s.bytes += n * 2 } catch (_: Exception) {} }
    }

    private fun wavHeader(dataBytes: Int): ByteBuffer {
        val b = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        b.put("RIFF".toByteArray()).putInt(36 + dataBytes).put("WAVE".toByteArray())
        b.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1).putInt(48000).putInt(96000).putShort(2).putShort(16)
        b.put("data".toByteArray()).putInt(dataBytes)
        b.flip()
        return b
    }
}
