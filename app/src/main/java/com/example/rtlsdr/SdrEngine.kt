package com.example.rtlsdr

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.os.Process
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.DataInputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.BlockingQueue
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.Locale
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.sin

/**
 * rtl_tcp client + FFT + waterfall buffer + demodulator.
 *
 * Two threads per session: the READER only reads the socket and records raw IQ (so recordings never have gaps);
 * the DSP thread does FFT + demodulation. If DSP falls behind, chunks are dropped for the display/audio only
 * (never for recording), so the picture always stays real-time instead of building up seconds of delay.
 */
class SdrEngine(private val host: String = "127.0.0.1") {
    companion object {
        const val N = 1024
        const val WF_H = 256
        const val DEFAULT_RATE = 2_400_000
        /** All multiples of 240 kS/s so audio decimation is exact. */
        val RATES = listOf(960_000, 1_920_000, 2_400_000)
        private const val CHUNK = 8192 // IQ samples per read
        private const val POOL = 24    // chunks buffered between reader and DSP (~0.2 s at 960 kS/s)
        private const val CMD_FREQ = 1
        private const val CMD_RATE = 2
        private const val CMD_GAIN_MODE = 3
        private const val CMD_GAIN = 4
        private const val CMD_AGC = 8
        private const val CMD_EXIT = 0x7e
        private const val GSM_SETTLE_NS = 150_000_000L // after retuning, let old samples drain
        private const val GSM_DWELL_NS = 150_000_000L  // listening time per channel (an FCCH comes every ~50 ms)
        const val APT_W = 2080; const val APT_H = 900 // satellite preview: ~900 lines = ~7.5 minutes, kept scrolling
    }

    var khz by mutableLongStateOf(433_920L)
    /** RADIO = normal receiver, ADSB = aircraft tracker on 1090 MHz. */
    var app by mutableStateOf(AppMode.RADIO)
    val adsb = AdsbDecoder()
    var aircraft by mutableStateOf<List<Aircraft>>(emptyList())
    var adsbMsgs by mutableIntStateOf(0)
    /** ADS-B screen: true = map, false = list. */
    var adsbMap by mutableStateOf(true)

    // ---- GSM scanner ----
    var gsmBand by mutableStateOf(GsmBand.GSM900)
    var gsmScanning by mutableStateOf(false)
    var gsmResults by mutableStateOf<List<GsmResult>>(emptyList())
    var gsmProgress by mutableStateOf("")
    private val gsmDet = FcchDetector()
    @Volatile private var gsmChannels: List<GsmChannel> = emptyList()
    @Volatile private var gsmIdx = 0
    @Volatile private var gsmSettle = true
    @Volatile private var gsmT0 = 0L
    private val gsmAcc = ArrayList<GsmResult>()

    // ---- GSM verify: a live feed of what's demodulated on one channel, like grgsm_livemon's console ----
    var verifying by mutableStateOf(false)
    var verifyArfcn by mutableIntStateOf(-1)
    /** Every raw burst and every fully-decoded message seen so far this session, newest first. */
    var verifyLog by mutableStateOf<List<VerifyLine>>(emptyList())
    private val verifyLogAcc = ArrayList<VerifyLine>()
    private val gsmVerifier = GsmCchVerifier()
    private val imsiCatcher = GsmImsiCatcher()
    var imsiRows by mutableStateOf<List<ImsiRow>>(emptyList()); private set
    /** Which of the three live-feed views (HEX console, IMSI catcher, Wireshark-style packet list) is shown. */
    var gsmView by mutableStateOf(GsmView.HEX)
    /** Fully FEC-verified messages this verify session, newest first -- the Wireshark-style packet list. */
    var gsmPackets by mutableStateOf<List<GsmPacket>>(emptyList()); private set
    private val gsmPacketsAcc = ArrayList<GsmPacket>()
    private var gsmPktNo = 0

    // ---- GSM cell locator (OpenCellID) ----
    /** OpenCellID API key (opencellid.org/register), persisted by the UI alongside the GSMTAP host. */
    var ocidKey by mutableStateOf("")
    var cellFix by mutableStateOf<CellFix?>(null); private set
    var cellLookupStatus by mutableStateOf("")
    private val net = Executors.newSingleThreadExecutor()

    /** Looks up one row's serving cell (its MCC/MNC/LAC/CI, from SI3) on OpenCellID -- tap a row in the
        IMSI table to call this for that row, rather than guessing which cell the person wants. Runs off
        the UI thread; updates [cellFix]/[cellLookupStatus] when it returns. */
    fun lookupCell(row: ImsiRow) {
        if (row.mcc.isEmpty() || row.lac.isEmpty() || row.cell.isEmpty()) {
            cellLookupStatus = "this row has no serving cell decoded yet (need SI3 on this channel)"; return
        }
        if (ocidKey.isBlank()) { cellLookupStatus = "set an OpenCellID API key in Settings → GSM first"; return }
        cellFix = null
        cellLookupStatus = "looking up..."
        val key = ocidKey
        net.execute {
            val fix = CellLocator.lookup(key, row.mcc, row.mnc, row.lac, row.cell)
            main.post {
                cellFix = fix
                cellLookupStatus = if (fix != null) "" else "lookup failed (unknown cell, bad key, or no network)"
            }
        }
    }
    var imsiBeepMuted by mutableStateOf(false)
    private var toneGen: android.media.ToneGenerator? = null
    private fun beepNewImsi() {
        if (imsiBeepMuted) return
        try {
            val tg = toneGen ?: android.media.ToneGenerator(android.media.AudioManager.STREAM_NOTIFICATION, 90).also { toneGen = it }
            tg.startTone(android.media.ToneGenerator.TONE_PROP_BEEP, 180)
        } catch (_: Exception) {}
    }
    private val gsmtap = GsmtapSender()
    private var gsmtapHostState by mutableStateOf("127.0.0.1:4729")
    /** Where decoded messages are sent as GSMTAP: "ip" or "ip:port" (default port 4729, what Wireshark listens on). */
    var gsmtapHost: String
        get() = gsmtapHostState
        set(v) {
            val t = v.trim().ifEmpty { "127.0.0.1" }
            val colon = t.lastIndexOf(':')
            val h = if (colon > 0 && t.indexOf(':') == colon) t.substring(0, colon) else t
            val p = if (colon > 0 && t.indexOf(':') == colon) t.substring(colon + 1).toIntOrNull() ?: 4729 else 4729
            gsmtap.host = h; gsmtap.port = p
            gsmtapHostState = "$h:$p"
        }
    private var seenDrop = 0L

    // ---- Satellite (NOAA APT) ----
    val aptDecoder = AptDecoder()
    var aptLines by mutableIntStateOf(0)
    var aptLocked by mutableStateOf(false)
    var aptImageVersion by mutableIntStateOf(0)
    val aptBitmap: Bitmap = Bitmap.createBitmap(APT_W, APT_H, Bitmap.Config.ARGB_8888).also { it.eraseColor(0xFF000000.toInt()) }
    private val aptPixels = IntArray(APT_W * APT_H) { 0xFF000000.toInt() }

    /** Scrolls the preview bitmap up one line and draws [row] (2080 grayscale 0-255 values) at the bottom. Main thread only. */
    fun pushAptLine(row: IntArray) {
        System.arraycopy(aptPixels, APT_W, aptPixels, 0, APT_W * (APT_H - 1))
        val base = APT_W * (APT_H - 1)
        for (x in 0 until APT_W) { val g = row[x]; aptPixels[base + x] = (0xFF shl 24) or (g shl 16) or (g shl 8) or g }
        aptBitmap.setPixels(aptPixels, 0, APT_W, 0, 0, APT_W, APT_H)
    }
    private class Saved(val khz: Long, val rate: Int, val gain: Float, val auto: Boolean, val direct: Boolean)
    private var saved: Saved? = null
    var gain by mutableFloatStateOf(30f)
    var auto by mutableStateOf(true)
    var sampleRate by mutableIntStateOf(DEFAULT_RATE)
    var status by mutableStateOf("looking for device..."); private set
    var live by mutableStateOf(false); private set
    /** "vid:pid" of the detected dongle, or null. */
    var device by mutableStateOf<String?>(null)
    var ppm by mutableIntStateOf(0)
    var palette by mutableStateOf<Palette>(Palettes.all[0])
    var fill by mutableStateOf(true)
    var showPeaks by mutableStateOf(false)
    var showHold by mutableStateOf(false)
    var showBands by mutableStateOf(false)
    /** Direction of the last frequency change, for the digit roll animation. */
    var tickUp by mutableStateOf(true)
    /** Peak-hold trace (dB), decays slowly. */
    val hold = FloatArray(N) { -200f }
    var direct by mutableStateOf(false)
    var offsetTuning by mutableStateOf(false)
    /** Waterfall adds one row every [wfDiv] frames (1 = fastest). */
    var wfDiv by mutableIntStateOf(1)
    /** Display level shift in dB (moves the colour/graph window). */
    var shift by mutableFloatStateOf(0f)
    val loEff: Float get() = lo + shift
    val hiEff: Float get() = hi + shift
    private var wfTick = 0
    /** True from START until STOP (or until the connection fails). */
    var active by mutableStateOf(false)
    var frame by mutableIntStateOf(0); private set
    var spectrum = FloatArray(N) { -100f }; private set
    var lo = -90f; private set
    var hi = -30f; private set
    val waterfall: Bitmap = Bitmap.createBitmap(N, WF_H, Bitmap.Config.ARGB_8888).also { it.eraseColor(0xFF000000.toInt()) }
    var level by mutableFloatStateOf(-100f); private set
    /** Time of the last chunk dropped because DSP could not keep up (for the LAG indicator). */
    @Volatile var lastDropMs = 0L; private set
    var recorder: Recorder? = null
    val demod = Demodulator().also { d -> d.tap = { a, n -> recorder?.writeAudio(a, n) } }

    private val wfPixels = IntArray(N * WF_H) { 0xFF000000.toInt() }
    private val main = Handler(Looper.getMainLooper())
    private val cmdExec = Executors.newSingleThreadExecutor()
    private val lock = Any()
    @Volatile private var gen = 0
    @Volatile private var socket: Socket? = null
    private var out: OutputStream? = null

    private val window = FloatArray(N) { (0.5 - 0.5 * cos(2 * PI * it / (N - 1))).toFloat() }
    private val cosT = FloatArray(N / 2) { cos(2 * PI * it / N).toFloat() }
    private val sinT = FloatArray(N / 2) { (-sin(2 * PI * it / N)).toFloat() }

    fun showStatus(s: String) { status = s }

    /** Connect to the driver's rtl_tcp server that was just started on [port]. */
    fun connect(port: Int) {
        val g = ++gen
        live = false
        status = "connecting..."
        Thread { runLoop(g, port) }.apply { isDaemon = true }.start()
    }

    /** Stops everything; asks the driver to shut itself down so the dongle is released. */
    fun disconnect() {
        gen++
        live = false
        recorder?.stopAll()
        demod.stop()
        val s = socket
        socket = null
        if (s != null) Thread {
            try { synchronized(lock) { out?.let { it.write(ByteBuffer.allocate(5).put(CMD_EXIT.toByte()).putInt(0).array()); it.flush() } } } catch (_: Exception) {}
            try { s.close() } catch (_: Exception) {}
        }.start()
    }

    /** Tells a driver instance on [port] to exit (frees the dongle) when we never got to connect to it. */
    fun killDriver(port: Int) {
        Thread {
            try {
                Socket().use {
                    it.connect(InetSocketAddress(host, port), 500)
                    it.getOutputStream().apply { write(ByteBuffer.allocate(5).put(CMD_EXIT.toByte()).putInt(0).array()); flush() }
                }
            } catch (_: Exception) {}
        }.start()
    }

    fun sendPpm(v: Int) = send(0x05, v)
    fun sendDirect(on: Boolean) = send(0x09, if (on) 2 else 0)
    fun sendOffsetTuning(on: Boolean) = send(0x0a, if (on) 1 else 0)

    fun tune(khz: Long) { java.util.Arrays.fill(hold, -200f); send(CMD_FREQ, (khz * 1000L).toInt()) }

    /** Changes the dongle sample rate live (an IQ recording in progress is stopped, its rate would change). */
    fun applySampleRate(r: Int) {
        if (r == sampleRate) return
        if (recorder?.iqOn == true) recorder?.stopIq()
        sampleRate = r
        demod.sampleRate = r
        java.util.Arrays.fill(hold, -200f)
        send(CMD_RATE, r)
    }

    /** Switches between RADIO, ADS-B and GSM, saving/restoring the radio's frequency, rate and gain. */
    fun switchApp(a: AppMode) {
        if (a == app) return
        recorder?.stopAll()
        gsmScanning = false; verifying = false
        if (app == AppMode.RADIO) saved = Saved(khz, sampleRate, gain, auto, direct)
        app = a
        if (a != AppMode.RADIO && direct) { direct = false; sendDirect(false) }
        when (a) {
            AppMode.RADIO -> saved?.let { sv ->
                khz = sv.khz; tune(khz)
                applySampleRate(sv.rate)
                gain = sv.gain; auto = sv.auto; setGain((sv.gain * 10).toInt(), sv.auto)
                if (sv.direct) { direct = true; sendDirect(true) }
            }
            AppMode.ADSB -> {
                khz = 1_090_000L; tune(khz)
                applySampleRate(2_000_000)
                gain = 45f; auto = false; setGain(450, false)
            }
            AppMode.GSM -> {
                khz = GsmBands.channels(gsmBand).first().khz; tune(khz)
                applySampleRate(FcchDetector.RATE)
                gain = 40f; auto = false; setGain(400, false)
                gsmResults = emptyList(); gsmProgress = ""
                verifying = false; verifyLogAcc.clear(); verifyLog = emptyList()
            }
            AppMode.SATELLITE -> {
                khz = 137_100L; tune(khz)
                applySampleRate(1_920_000)
                // AGC by default: APT signal strength varies a lot with antenna and satellite elevation over
                // a pass, and the decoder's sync no longer depends on hitting one specific fixed gain (see Apt.kt)
                auto = true; setGain(0, true)
                aptDecoder.reset(); aptLines = 0; aptLocked = false; aptImageVersion++
            }
        }
    }

    /** Starts one sweep over every channel of [gsmBand]: tune, wait for the PLL/queue to settle, listen for FCCH bursts. */
    fun startGsmScan() {
        if (!live || app != AppMode.GSM) return
        gsmChannels = GsmBands.channels(gsmBand)
        gsmAcc.clear(); gsmResults = emptyList()
        gsmIdx = 0; gsmSettle = true
        tune(gsmChannels[0].khz)
        gsmT0 = System.nanoTime()
        gsmProgress = "scanning 0/${gsmChannels.size}"
        gsmScanning = true
    }

    fun stopGsmScan() { gsmScanning = false; gsmProgress = "stopped" }

    /** Tunes to [khz] and streams a live feed of what's demodulated there, like grgsm_livemon's console:
        every burst that syncs shows up as raw hex right away; bursts that also pass the full decode chain
        (sync group → deinterleave → Viterbi → Fire CRC) show up as a verified L3 message. Runs until
        [stopVerify] is called, since real traffic is intermittent rather than a yes/no in a few seconds. */
    fun startVerify(khz: Long) {
        if (app != AppMode.GSM) return
        gsmScanning = false
        applySampleRate(GsmCchVerifier.RATE)
        tune(khz)
        // seed the receiver with what the scan measured for this channel: the real ARFCN (for GSMTAP) and the
        // dongle's frequency error from the FCCH tone (the verifier keeps refining it itself once bursts decode)
        val known = gsmResults.firstOrNull { it.khz == khz }
        gsmVerifier.reset()
        imsiCatcher.reset(); imsiRows = emptyList()
        gsmVerifier.freqOffsetHz = known?.offsetHz ?: 0f
        verifyArfcn = known?.arfcn ?: GsmBands.arfcnFor(khz) // typed-in frequency: no scan result, derive the ARFCN
        verifyLogAcc.clear(); verifyLog = emptyList()
        gsmPacketsAcc.clear(); gsmPackets = emptyList(); gsmPktNo = 0
        cellFix = null; cellLookupStatus = ""
        verifying = true
    }

    fun stopVerify() { verifying = false }

    private fun gsmVerifyStep(fi: FloatArray, fq: FloatArray) {
        if (!verifying) return
        // chunks were dropped since the last step: burst spacing is no longer valid, forget partial groups
        val drop = lastDropMs
        if (drop != seenDrop) { seenDrop = drop; gsmVerifier.markGap() }
        gsmVerifier.showRaw = false; gsmVerifier.showTried = false // only decoded messages, like grgsm_livemon
        val results = gsmVerifier.process(fi, fq, CHUNK, sampleRate)
        val raws = gsmVerifier.rawBursts
        val tried = gsmVerifier.triedGroups
        if (results.none { it.isCcch } && raws.isEmpty() && tried.isEmpty()) return
        val now = System.currentTimeMillis()
        for (r in raws) verifyLogAcc.add(0, VerifyLine(0, "TSC${r.tsc}  ${r.hex}", now))
        for (t in tried) verifyLogAcc.add(0, VerifyLine(1, "4 grouped, Fire check failed:  ${t.hex}", now))
        var imsiChanged = false; var newImsi = false
        var pktChanged = false
        for (res in results) {
            if (!res.isCcch) continue // valid block of another timeslot (SDCCH/SACCH...): not what livemon shows
            verifyLogAcc.add(0, VerifyLine(2, res.hex, now))
            val frame = Gsmtap.buildFrame(verifyArfcn, res.bytes)
            gsmtap.send(verifyArfcn, res.bytes) // same format/port grgsm_livemon uses; Wireshark can show it too
            if (recorder?.pcapOn == true) recorder?.writePcapPacket(frame)
            gsmPktNo++
            gsmPacketsAcc.add(0, GsmPacket(gsmPktNo, now, verifyArfcn, Gsmtap.channelName(res.bytes), Gsmtap.messageName(res.bytes), res.bytes.size, res.hex))
            pktChanged = true
            val before = imsiCatcher.rows.size
            imsiCatcher.feed(res.bytes, now)
            val added = imsiCatcher.rows.size - before
            if (added > 0) {
                imsiChanged = true
                // rows are inserted newest-first; id>0 means a genuinely new IMSI (id==0 is a TMSI reassociation
                // of an already-known IMSI, which shouldn't beep every time one is paged again)
                for (idx in 0 until added) if (imsiCatcher.rows[idx].id > 0) newImsi = true
            }
        }
        while (verifyLogAcc.size > 300) verifyLogAcc.removeAt(verifyLogAcc.size - 1)
        while (gsmPacketsAcc.size > 300) gsmPacketsAcc.removeAt(gsmPacketsAcc.size - 1)
        val snap = verifyLogAcc.toList()
        val imsiSnap = if (imsiChanged) imsiCatcher.rows.toList() else null
        val pktSnap = if (pktChanged) gsmPacketsAcc.toList() else null
        if (newImsi) beepNewImsi()
        main.post { if (verifying) { verifyLog = snap; if (imsiSnap != null) imsiRows = imsiSnap; if (pktSnap != null) gsmPackets = pktSnap } }
    }

    /** One DSP step of the GSM sweep (runs on the DSP thread, see dspLoop). */
    private fun gsmStep(fi: FloatArray, fq: FloatArray) {
        if (!gsmScanning) return
        val ch = gsmChannels.getOrNull(gsmIdx)
        if (ch == null) { gsmScanning = false; return }
        val now = System.nanoTime()
        if (gsmSettle) {
            if (now - gsmT0 < GSM_SETTLE_NS) return // samples from the old frequency are still draining
            gsmSettle = false; gsmDet.reset(); gsmT0 = now
        }
        gsmDet.process(fi, fq, CHUNK)
        if (now - gsmT0 >= GSM_DWELL_NS) {
            gsmAcc.add(GsmResult(ch.arfcn, ch.khz, gsmDet.powerDb, gsmDet.hits, gsmDet.offsetHz))
            gsmIdx++
            val snap = gsmAcc.toList()
            val next = gsmChannels.getOrNull(gsmIdx)
            val text = if (next == null) "done: ${snap.count { it.hits > 0 }} beacon channels found"
                       else "ARFCN ${next.arfcn}  ${"%.1f".format(Locale.US, next.khz / 1000.0)} MHz  (${gsmIdx + 1}/${gsmChannels.size})"
            main.post { gsmResults = snap; gsmProgress = text }
            if (next == null) gsmScanning = false
            else { tune(next.khz); gsmSettle = true; gsmT0 = System.nanoTime() }
        }
    }

    fun setGain(tenthsDb: Int, auto: Boolean) {
        send(CMD_GAIN_MODE, if (auto) 0 else 1)
        send(CMD_AGC, if (auto) 1 else 0)
        if (!auto) send(CMD_GAIN, tenthsDb)
    }

    private fun send(cmd: Int, value: Int) {
        cmdExec.execute {
            try {
                synchronized(lock) {
                    val o = out ?: return@execute
                    o.write(ByteBuffer.allocate(5).put(cmd.toByte()).putInt(value).array())
                    o.flush()
                }
            } catch (_: Exception) {}
        }
    }

    private fun runLoop(g: Int, port: Int) {
        fun alive() = g == gen
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        var s: Socket? = null
        for (i in 0 until 12) {
            if (!alive()) return
            try {
                s = Socket().apply { connect(InetSocketAddress(host, port), 1000); tcpNoDelay = true; soTimeout = 5000; receiveBufferSize = 1 shl 20 }
                break
            } catch (_: Exception) { Thread.sleep(400) }
        }
        if (s == null) { if (alive()) main.post { active = false; status = "can't reach driver - tap to retry" }; return }
        socket = s
        var started = false
        val stop = AtomicBoolean(false)
        try {
            val input = DataInputStream(s.getInputStream())
            synchronized(lock) { out = s.getOutputStream() }
            input.readFully(ByteArray(12)) // "RTL0" + tuner type + gain count
            demod.start(); started = true
            demod.sampleRate = sampleRate
            send(CMD_RATE, sampleRate)
            tune(khz)
            setGain((gain * 10).toInt(), auto)
            if (ppm != 0) sendPpm(ppm)
            if (direct) sendDirect(true)
            if (offsetTuning) sendOffsetTuning(true)
            main.post { if (alive()) { live = true; status = "" } }

            val free: BlockingQueue<ByteArray> = ArrayBlockingQueue(POOL)
            val full: BlockingQueue<ByteArray> = ArrayBlockingQueue(POOL)
            repeat(POOL) { free.add(ByteArray(CHUNK * 2)) }
            val scratch = ByteArray(CHUNK * 2)
            Thread { dspLoop(g, stop, full, free) }.apply { isDaemon = true }.start()

            while (alive()) {
                val buf = free.poll()
                val target = buf ?: scratch
                input.readFully(target)
                recorder?.writeIq(target, target.size) // always: recordings are gap-free
                if (buf != null) full.put(buf) else lastDropMs = System.currentTimeMillis()
            }
        } catch (_: Exception) {
        } finally {
            stop.set(true)
            try { s.close() } catch (_: Exception) {}
            if (alive()) {
                recorder?.stopAll()
                if (started) demod.stop()
                synchronized(lock) { out = null }
                main.post { live = false; active = false; status = "disconnected - tap to retry" }
            }
        }
    }

    /** FFT for the display + demodulation. Runs apart from the socket reader. */
    private fun dspLoop(g: Int, stop: AtomicBoolean, full: BlockingQueue<ByteArray>, free: BlockingQueue<ByteArray>) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        val fi = FloatArray(CHUNK); val fq = FloatArray(CHUNK)
        val re = FloatArray(N); val im = FloatArray(N)
        val acc = FloatArray(N); var cnt = 0
        var last = System.nanoTime()
        var lastAds = last
        while (g == gen && !stop.get()) {
            val raw = full.poll(100, TimeUnit.MILLISECONDS) ?: continue
            for (k in 0 until CHUNK) {
                fi[k] = ((raw[2 * k].toInt() and 0xFF) - 127.5f) * (1f / 127.5f)
                fq[k] = ((raw[2 * k + 1].toInt() and 0xFF) - 127.5f) * (1f / 127.5f)
            }
            val adsbMode = app == AppMode.ADSB
            if (adsbMode) adsb.process(raw, CHUNK)
            free.offer(raw)
            if (app == AppMode.GSM) {
                if (verifying) gsmVerifyStep(fi, fq) else gsmStep(fi, fq)
                continue // no spectrum or audio needed while scanning/verifying
            }
            if (app == AppMode.SATELLITE) {
                val before = aptDecoder.linesDecoded
                aptDecoder.process(fi, fq, CHUNK, sampleRate)
                val lines = aptDecoder.linesDecoded; val lock = aptDecoder.locked
                if (lines != before) {
                    val row = aptDecoder.image.last()
                    main.post { if (g == gen) { pushAptLine(row); aptLines = lines; aptLocked = lock; aptImageVersion++ } }
                }
            }
            // spectrum: 2 of the 8 blocks in each chunk is plenty for a 25 fps display and keeps the CPU free for audio
            var b = 0
            while (b < CHUNK / N) {
                for (k in 0 until N) { re[k] = fi[b * N + k] * window[k]; im[k] = fq[b * N + k] * window[k] }
                fft(re, im)
                for (k in 0 until N) {
                    val idx = (k + N / 2) % N
                    acc[k] += re[idx] * re[idx] + im[idx] * im[idx]
                }
                cnt++
                b += 4
            }
            if (!adsbMode) demod.process(fi, fq, CHUNK)
            val now = System.nanoTime()
            if (adsbMode && now - lastAds > 500_000_000L) {
                lastAds = now
                val list = adsb.snapshot(System.currentTimeMillis())
                val tot = adsb.total
                main.post { if (g == gen) { aircraft = list; adsbMsgs = tot } }
            }
            if (now - last > 40_000_000L && cnt > 0) {
                val c = cnt
                val db = FloatArray(N) { 10f * log10(acc[it] / c / (N.toFloat() * N) + 1e-12f) }
                acc.fill(0f); cnt = 0; last = now
                main.post { if (g == gen) applyFrame(db) }
            }
        }
    }

    private fun applyFrame(db: FloatArray) {
        var sum = 0f; var mx = -200f
        for (v in db) { sum += v; if (v > mx) mx = v }
        val target = mx + 3f
        hi += (target - hi) * (if (target > hi) 0.5f else 0.03f) // fast attack, slow decay
        lo = lo * 0.95f + (sum / N - 3f) * 0.05f
        if (hi - lo < 30f) hi = lo + 30f
        val prev = spectrum
        spectrum = FloatArray(N) { prev[it] * 0.5f + db[it] * 0.5f }
        for (i in 0 until N) hold[i] = if (spectrum[i] > hold[i]) spectrum[i] else hold[i] - 0.4f
        if (++wfTick >= wfDiv) {
            wfTick = 0
            val l = loEff; val h = hiEff
            System.arraycopy(wfPixels, 0, wfPixels, N, N * (WF_H - 1))
            for (i in 0 until N) wfPixels[i] = color(((db[i] - l) / (h - l)).coerceIn(0f, 1f))
            waterfall.setPixels(wfPixels, 0, N, 0, 0, N, WF_H)
        }
        level = demod.level
        frame++
    }

    private fun color(t: Float): Int = palette.lut[(t.coerceIn(0f, 1f) * 255f).toInt()]

    private fun fft(re: FloatArray, im: FloatArray) {
        var j = 0
        for (i in 1 until N) {
            var bit = N shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j xor bit
            if (i < j) {
                var t = re[i]; re[i] = re[j]; re[j] = t
                t = im[i]; im[i] = im[j]; im[j] = t
            }
        }
        var len = 2
        while (len <= N) {
            val half = len / 2; val step = N / len
            var i = 0
            while (i < N) {
                for (k in 0 until half) {
                    val wr = cosT[k * step]; val wi = sinT[k * step]
                    val a = i + k; val b = a + half
                    val tr = re[b] * wr - im[b] * wi
                    val ti = re[b] * wi + im[b] * wr
                    re[b] = re[a] - tr; im[b] = im[a] - ti
                    re[a] += tr; im[a] += ti
                }
                i += len
            }
            len = len shl 1
        }
    }
}
