package com.example.rtlsdr

import android.content.ActivityNotFoundException
import android.content.res.Configuration
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbManager
import android.net.Uri
import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.ui.unit.Dp
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.viewinterop.AndroidView
import android.webkit.WebView
import android.webkit.WebSettings
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.PI
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

private const val MIN_KHZ = 24_000L
private const val MIN_DIRECT_KHZ = 100L
private const val MAX_KHZ = 1_766_000L
private val Red = Color(0xFFFF5252)
private val Mono = FontFamily(Font(R.font.space_mono))
private val LocalPal = compositionLocalOf<Palette> { Palettes.all[0] }

/** Common RTL2832U dongle USB ids (vendor to product). */
private val RTL_IDS = listOf(
    0x0bda to 0x2832, 0x0bda to 0x2838, 0x0413 to 0x6680, 0x0413 to 0x6f0f, 0x0458 to 0x707f,
    0x0ccd to 0x00a9, 0x0ccd to 0x00b3, 0x0ccd to 0x00b4, 0x0ccd to 0x00b5, 0x0ccd to 0x00b7,
    0x0ccd to 0x00b8, 0x0ccd to 0x00b9, 0x0ccd to 0x00c0, 0x0ccd to 0x00c6, 0x0ccd to 0x00d3,
    0x0ccd to 0x00d7, 0x0ccd to 0x00e0, 0x1554 to 0x5020, 0x15f4 to 0x0131, 0x15f4 to 0x0133,
    0x185b to 0x0620, 0x185b to 0x0650, 0x185b to 0x0680, 0x1b80 to 0xd393, 0x1b80 to 0xd394,
    0x1b80 to 0xd395, 0x1b80 to 0xd397, 0x1b80 to 0xd398, 0x1b80 to 0xd39d, 0x1b80 to 0xd3a4,
    0x1b80 to 0xd3a8, 0x1b80 to 0xd3af, 0x1b80 to 0xd3b0, 0x1d19 to 0x1101, 0x1d19 to 0x1102,
    0x1d19 to 0x1103, 0x1d19 to 0x1104, 0x1f4d to 0xa803, 0x1f4d to 0xb803, 0x1f4d to 0xc803,
    0x1f4d to 0xd286, 0x1f4d to 0xd803
)

class MainActivity : ComponentActivity() {
    private val engine = SdrEngine()
    private var port = 0

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) { detect() }
    }

    private val driverLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == RESULT_OK) { if (engine.active) engine.connect(port) }
        else { engine.active = false; engine.showStatus(r.data?.getStringExtra("detailed_exception_message") ?: "driver failed - tap to retry") }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        engine.recorder = Recorder(applicationContext) { Toast.makeText(this, it, Toast.LENGTH_LONG).show() }
        ContextCompat.registerReceiver(this, usbReceiver, IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }, ContextCompat.RECEIVER_EXPORTED)
        setContent {
            val pal = engine.palette
            CompositionLocalProvider(LocalPal provides pal) {
                MaterialTheme(colorScheme = darkColorScheme(background = Color.Black, surface = Color.Black, primary = pal.accent)) {
                    Screen(engine, ::startDriver, ::stopRadio, ::detect)
                }
            }
        }
    }

    override fun onResume() { super.onResume(); detect() }

    /** Looks for an RTL-SDR on the USB bus (no permission needed just to list devices). */
    private fun detect() {
        val usb = getSystemService(Context.USB_SERVICE) as UsbManager
        val d = usb.deviceList.values.firstOrNull { (it.vendorId to it.productId) in RTL_IDS }
        engine.device = d?.let { "%04x:%04x".format(it.vendorId, it.productId) }
        if (d != null) {
            if (!engine.active) engine.showStatus("found RTL-SDR (${engine.device})")
        } else {
            if (engine.active) stopRadio()
            engine.showStatus("no RTL-SDR found - plug it in")
        }
    }

    /** Fresh port every time so a leftover driver instance can never block us. */
    private fun startDriver() {
        if (port != 0 && !engine.live) engine.killDriver(port) // free the dongle if an old driver instance is waiting
        engine.disconnect()
        engine.active = true
        engine.showStatus("starting driver...")
        port = Random.nextInt(20000, 40000)
        try {
            driverLauncher.launch(Intent(Intent.ACTION_VIEW, Uri.parse(
                "iqsrc://-a 127.0.0.1 -p $port -s ${engine.sampleRate} -f ${engine.khz * 1000} -g 0")))
        } catch (e: ActivityNotFoundException) {
            engine.active = false
            engine.showStatus("install the 'RTL-SDR driver' app")
        }
    }

    private fun stopRadio() {
        if (port != 0 && !engine.live) engine.killDriver(port)
        engine.disconnect() // also asks a connected driver to exit
        engine.active = false
        engine.showStatus("stopped")
    }

    override fun onDestroy() {
        try { unregisterReceiver(usbReceiver) } catch (_: Exception) {}
        engine.disconnect()
        super.onDestroy()
    }
}

/** Drag left/right to move the frequency (the display pans like a map). */
private fun Modifier.swipeTune(spanKhz: () -> Float, onKhz: (Long) -> Unit): Modifier = pointerInput(Unit) {
    var acc = 0f
    detectHorizontalDragGestures(onDragStart = { acc = 0f }) { change, dx ->
        change.consume()
        acc += -dx / size.width * spanKhz()
        val whole = acc.toLong()
        if (whole != 0L) { onKhz(whole); acc -= whole }
    }
}

private fun fmtBw(hz: Float) = if (hz >= 10_000) "%.0fk".format(hz / 1000) else "%.1fk".format(hz / 1000)
private fun stamp() = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())

@Composable
private fun Screen(engine: SdrEngine, startDriver: () -> Unit, stopRadio: () -> Unit, detect: () -> Unit) {
    val pal = LocalPal.current
    val khz = engine.khz
    val rec = engine.recorder!!
    var mode by remember { mutableStateOf(Mode.WFM) }
    var bw by remember { mutableFloatStateOf(Mode.WFM.defBw) }
    var sql by remember { mutableFloatStateOf(Demodulator.SQL_OFF) }
    var volume by remember { mutableFloatStateOf(0.8f) }
    var muted by remember { mutableStateOf(false) }
    var typing by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    val tm = rememberTextMeasurer()
    val ctx = LocalContext.current
    // Spectrum display zoom: 1f = full captured span (no zoom); smaller = a narrower slice, stretched across
    // the same screen width. Purely a display crop of the bins already captured -- it never touches the
    // actual tuned frequency or sample rate. specPan is the fractional centre (0..1) of the visible window.
    var specZoom by remember { mutableFloatStateOf(1f) }
    var specPan by remember { mutableFloatStateOf(0.5f) }
    fun clampPan(zoom: Float, pan: Float) = pan.coerceIn(zoom / 2f, 1f - zoom / 2f)
    val radio = engine.app == AppMode.RADIO

    fun change(v: Long) {
        val nv = v.coerceIn(if (engine.direct) MIN_DIRECT_KHZ else MIN_KHZ, MAX_KHZ)
        engine.tickUp = nv >= engine.khz
        engine.khz = nv
        engine.tune(nv)
    }
    // One gesture detector, not two: a drag AND a pinch were previously handled by separate competing
    // pointerInput blocks reading the same touch stream, which on a real device raced each other and
    // produced exactly the jumpy/laggy tuning this was meant to avoid. Pan and zoom now come from the
    // same detector, so there's only ever one thing interpreting a given touch at a time.
    val specGesture = Modifier.pointerInput(radio) {
        var accKhz = 0f
        var lastTuneMs = 0L
        detectTransformGestures(panZoomLock = false) { _, pan, zoom, _ ->
            if (kotlin.math.abs(zoom - 1f) > 0.001f) {
                specZoom = (specZoom / zoom).coerceIn(0.02f, 1f)
                specPan = clampPan(specZoom, specPan)
            }
            if (pan.x != 0f) {
                if (specZoom < 0.999f) {
                    // zoomed in: pan the visible window across the already-captured spectrum instead of
                    // retuning, content-under-your-finger style (a full-width drag crosses the window)
                    val fracFull = -pan.x / size.width
                    specPan = clampPan(specZoom, specPan + fracFull * specZoom)
                } else if (radio) {
                    accKhz += -pan.x / size.width * (engine.sampleRate / 1000f)
                    // throttled: a touch stream can fire this callback ~60-120x/sec, and sending a real
                    // hardware retune every single time floods the tuner faster than it can settle --
                    // that backlog is exactly what showed up as "slides, then the spectrum catches up
                    // late". Capping actual retunes to ~12/sec (one every 80ms) keeps the drag feeling
                    // responsive (specPan/specZoom above are never throttled, only the real hardware call)
                    // while letting the dongle actually keep up.
                    val now = System.currentTimeMillis()
                    if (now - lastTuneMs >= 80) {
                        val whole = accKhz.toLong()
                        if (whole != 0L) { change(engine.khz + whole); accKhz -= whole }
                        lastTuneMs = now
                    }
                }
            }
        }
    }
    val doubleTapResetZoom = Modifier.pointerInput(Unit) {
        detectTapGestures(onDoubleTap = { specZoom = 1f; specPan = 0.5f })
    }
    val found = engine.device != null
    val gsm = engine.app == AppMode.GSM
    val sat = engine.app == AppMode.SATELLITE

    val config = LocalConfiguration.current
    val isLandscape = config.orientation == Configuration.ORIENTATION_LANDSCAPE
    // In landscape, RX's spectrum gets 15 points more height (and the waterfall below it 15 less);
    // the other modes, and portrait, keep the normal 35/65 split.
    val specH = if (radio) { if (isLandscape) 0.5f else 0.35f } else 0.2f

    @Composable
    fun ColumnScope.headerBlock() {
if (radio) {
            // Frequency
            Row(Modifier.fillMaxWidth().padding(top = if (isLandscape) 4.dp else 40.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.Bottom) {
                FrequencyDisplay(khz, engine.tickUp, pal.text, pal.dim,
                    onDelta = { change(engine.khz + it) }, onTap = { typing = true })
                Spacer(Modifier.width(8.dp))
                Label("MHz")
            }
            Spacer(Modifier.height(14.dp))

            // Modes
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Mode.values().forEach { m ->
                    val sel = m == mode
                    Text(m.label, fontFamily = Mono, fontSize = 14.sp, color = if (sel) Color.Black else pal.dim,
                        modifier = Modifier
                            .background(if (sel) pal.accent else pal.panel)
                            .clickable(remember { MutableInteractionSource() }, null) {
                                mode = m; bw = m.defBw
                                engine.demod.bandwidth = m.defBw; engine.demod.mode = m
                            }
                            .padding(horizontal = 14.dp, vertical = 8.dp))
                }
            }
            Spacer(Modifier.height(14.dp))
            } else {
                Text(when { gsm -> "GSM SCANNER"; sat -> "SATELLITE  ·  NOAA APT"; else -> "ADS-B  ·  1090 MHz" },
                    color = pal.text, fontFamily = Mono, fontSize = 14.sp,
                    modifier = Modifier.padding(top = if (isLandscape) 4.dp else 44.dp, bottom = 10.dp))
                if (sat) Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.Bottom) {
                    FrequencyDisplay(khz, engine.tickUp, pal.text, pal.dim, onDelta = { change(engine.khz + it) }, onTap = { typing = true })
                    Spacer(Modifier.width(8.dp)); Label("MHz")
                }
            }

                }

    @Composable
    fun ColumnScope.spectrumBlock() {
if (!gsm) {
            // Spectrum: the ONLY place where swiping left/right changes the frequency
            val peakStyle = TextStyle(color = pal.text, fontFamily = Mono, fontSize = 9.sp)
            val zoomed = specZoom < 0.999f
            Canvas(Modifier.fillMaxWidth().weight(specH)
                .then(specGesture).then(doubleTapResetZoom)) {
                val f = engine.frame
                val sp = engine.spectrum; val lo = engine.loEff; val hi = engine.hiEff
                val w = size.width; val h = size.height
                for (i in 1..3) {
                    drawLine(pal.grid, Offset(w * i / 4, 0f), Offset(w * i / 4, h))
                    drawLine(pal.grid, Offset(0f, h * i / 4), Offset(w, h * i / 4))
                }

                // visible bin window: [binLo, binHi], a crop of the N captured bins stretched across the full
                // canvas width. zoom never re-requests different IQ data, just redraws a slice of what's there.
                val n = SdrEngine.N
                val binLo = ((specPan - specZoom / 2f) * (n - 1)).toInt().coerceIn(0, n - 2)
                val binHi = ((specPan + specZoom / 2f) * (n - 1)).toInt().coerceIn(binLo + 1, n - 1)
                fun yOf(v: Float) = h * (1f - ((v - lo) / (hi - lo)).coerceIn(0f, 1f))
                fun xOf(i: Int) = (i - binLo) / (binHi - binLo).toFloat() * w
                // fraction-of-full-span (0..1) -> screen x, for things not tied to a bin index (the tuned centre, bw box)
                fun xOfFrac(frac: Float) = (frac - (binLo / (n - 1f))) / ((binHi - binLo) / (n - 1f)) * w

                if (engine.showBands) {
                    // a thin strip along the bottom edge, not a full-height wash over the trace/waterfall --
                    // just enough to label what's there without hiding the signal underneath it
                    val centerMhz = engine.khz / 1000.0
                    val spanMhz = engine.sampleRate / 1e6
                    val bandLabelStyle = TextStyle(fontFamily = Mono, fontSize = 8.sp)
                    val stripH = 11.dp.toPx()
                    val stripTop = h - stripH
                    for (b in BandPlan.all) {
                        val loMhz = b.loKhz / 1000.0; val hiMhz = b.hiKhz / 1000.0
                        if (hiMhz < centerMhz - spanMhz / 2 || loMhz > centerMhz + spanMhz / 2) continue
                        val fracLo = 0.5f + ((loMhz - centerMhz) / spanMhz).toFloat()
                        val fracHi = 0.5f + ((hiMhz - centerMhz) / spanMhz).toFloat()
                        val left = xOfFrac(fracLo).coerceIn(0f, w); val right = xOfFrac(fracHi).coerceIn(0f, w)
                        if (right - left < 0.5f) continue
                        drawRect(b.color.copy(alpha = 0.35f), Offset(left, stripTop), androidx.compose.ui.geometry.Size(right - left, stripH))
                        drawLine(b.color.copy(alpha = 0.5f), Offset(left, stripTop), Offset(left, h), strokeWidth = 1f)
                        if (right - left > 18.dp.toPx()) {
                            val lw = tm.measure(b.name, bandLabelStyle).size.width
                            if (lw < right - left + 10.dp.toPx()) {
                                val lx = (left + 3.dp.toPx()).coerceAtMost((w - lw).coerceAtLeast(0f))
                                drawText(tm, b.name, Offset(lx, h - 10.dp.toPx()), bandLabelStyle.copy(color = pal.text))
                            }
                        }
                    }
                }

                val bwPx = bw / engine.sampleRate * w / specZoom
                val centerX = xOfFrac(0.5f)
                val x0 = if (mode == Mode.USB) centerX else if (mode == Mode.LSB) centerX - bwPx else centerX - bwPx / 2
                if (radio && centerX in -bwPx..(w + bwPx)) drawRect(pal.accent.copy(alpha = 0.13f), Offset(x0, 0f), androidx.compose.ui.geometry.Size(bwPx, h))
                if (centerX in 0f..w) drawLine(pal.dim, Offset(centerX, 0f), Offset(centerX, h), strokeWidth = 2f)

                if (engine.showHold) {
                    val hp = Path()
                    for (i in binLo..binHi) { if (i == binLo) hp.moveTo(xOf(i), yOf(engine.hold[i])) else hp.lineTo(xOf(i), yOf(engine.hold[i])) }
                    drawPath(hp, pal.dim, style = Stroke(width = 1.5f))
                }

                val path = Path()
                for (i in binLo..binHi) { if (i == binLo) path.moveTo(xOf(i), yOf(sp[i])) else path.lineTo(xOf(i), yOf(sp[i])) }
                if (engine.fill) {
                    val area = Path()
                    area.addPath(path)
                    area.lineTo(w, h); area.lineTo(0f, h); area.close()
                    drawPath(area, Brush.verticalGradient(listOf(pal.accent.copy(alpha = 0.40f), Color.Transparent), startY = 0f, endY = h))
                }
                drawPath(path, pal.accent, style = Stroke(width = 2f))

                if (engine.showPeaks) {
                    // local maxima well above the noise floor within the visible window, strongest 3, at least 48 bins apart
                    val thr = engine.lo + 12f
                    val cand = ArrayList<Int>()
                    for (i in maxOf(2, binLo) until minOf(n - 2, binHi)) {
                        if (sp[i] > thr && sp[i] > sp[i - 1] && sp[i] >= sp[i + 1] && sp[i] >= sp[i - 2] && sp[i] >= sp[i + 2]) cand.add(i)
                        if (cand.size >= 80) break
                    }
                    cand.sortByDescending { sp[it] }
                    val picked = ArrayList<Int>()
                    for (i in cand) { if (picked.size < 3 && picked.none { kotlin.math.abs(it - i) < 48 }) picked.add(i) }
                    val centerMhz = engine.khz / 1000.0
                    for (i in picked) {
                        val x = xOf(i); val y = yOf(sp[i])
                        val tri = Path().apply { moveTo(x - 5f, y - 12f); lineTo(x + 5f, y - 12f); lineTo(x, y - 4f); close() }
                        drawPath(tri, pal.text)
                        val fMhz = centerMhz + (i / (n - 1f) - 0.5f) * engine.sampleRate / 1e6
                        val label = "%.3f %.0f".format(Locale.US, fMhz, sp[i])
                        val lw = tm.measure(label, peakStyle).size.width
                        val lx = (x - lw / 2f).coerceIn(0f, (w - lw).coerceAtLeast(0f))
                        val ly = if (y < 34f) y + 6f else y - 30f
                        drawText(tm, label, Offset(lx, ly), peakStyle)
                    }
                }
            }
            val mhz = khz / 1000.0
            val n2 = SdrEngine.N
            val binLo2 = ((specPan - specZoom / 2f) * (n2 - 1))
            val binHi2 = ((specPan + specZoom / 2f) * (n2 - 1))
            fun edgeMhz(bin: Float) = mhz + (bin / (n2 - 1f) - 0.5f) * engine.sampleRate / 1e6
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                for (v in listOf(edgeMhz(binLo2), edgeMhz((binLo2 + binHi2) / 2f), edgeMhz(binHi2))) Label("%.3f".format(v), 10)
            }
            if (zoomed) Text("zoomed ${(1 / specZoom).roundToInt()}× · double-tap to reset",
                color = pal.dim, fontFamily = Mono, fontSize = 9.sp, modifier = Modifier.padding(bottom = 2.dp))

            }

                }

    @Composable
    fun ColumnScope.waterfallBlock() {
// Waterfall (display only)
            val img = remember { engine.waterfall.asImageBitmap() }
            if (radio) Canvas(Modifier.fillMaxWidth().weight(1f - specH)) {
                val f = engine.frame
                drawImage(img, srcSize = IntSize(SdrEngine.N, SdrEngine.WF_H),
                    dstSize = IntSize(size.width.toInt(), size.height.toInt()), filterQuality = FilterQuality.Low)
            } else if (gsm) GsmPanel(engine, Modifier.fillMaxWidth().weight(1f))
            else if (sat) SatellitePanel(engine, Modifier.fillMaxWidth().weight(1f))
            else AdsbPanel(engine, Modifier.fillMaxWidth().weight(0.8f))
            Spacer(Modifier.height(6.dp))

                }

    @Composable
    fun ColumnScope.controlsFooter() {
        // Same button block for every mode: in landscape it's pinned 90%-wide and centred at the bottom of
        // the control panel (so it never shifts up or down as the header above it changes between modes);
        // in portrait it stays exactly as before, full width, flowing right after the display.
        val btnWidth = if (isLandscape) Modifier.fillMaxWidth(0.9f).align(Alignment.CenterHorizontally) else Modifier.fillMaxWidth()
        val statusWidth = if (isLandscape) Modifier.fillMaxWidth(0.9f).align(Alignment.CenterHorizontally) else Modifier.fillMaxWidth()

        if (isLandscape) Spacer(Modifier.weight(1f))

        Box(statusWidth) { StatusLine(engine) { if (!engine.active) { if (found) startDriver() else detect() } } }

        // Bottom bar: START / STOP on its own row; MODE + settings gear share the row below
        val label = if (engine.active) "STOP" else "START"
        val color = when { engine.active -> Red; found -> pal.accent; else -> pal.dim }
        BarButton(label, color, btnWidth.padding(top = 6.dp)) {
            when { engine.active -> stopRadio(); found -> startDriver(); else -> detect() }
        }
        Row(btnWidth.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            BarButton("MODE  ·  " + when (engine.app) { AppMode.RADIO -> "RX"; AppMode.ADSB -> "ADS-B"; AppMode.GSM -> "GSM"; AppMode.SATELLITE -> "SAT" },
                pal.text, Modifier.weight(1f)) {
                engine.switchApp(when (engine.app) {
                    AppMode.RADIO -> AppMode.ADSB; AppMode.ADSB -> AppMode.GSM; AppMode.GSM -> AppMode.SATELLITE; AppMode.SATELLITE -> AppMode.RADIO
                })
            }
            val recording = rec.audioOn || rec.iqOn
            Box(Modifier.width(64.dp).height(44.dp).background(pal.panel)
                .clickable(remember { MutableInteractionSource() }, null) { showSettings = true },
                contentAlignment = Alignment.Center) {
                GearIcon(if (recording) Red else pal.text)
            }
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (isLandscape) {
            Row(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 14.dp, vertical = 10.dp)) {
                // A compact, top-aligned control panel (frequency, modes, start/mode/settings) sitting right
                // next to the display, the way a desktop SDR app's side panel hugs its spectrum view.
                Column(Modifier.weight(0.4f).fillMaxHeight()) {
                    headerBlock()
                    controlsFooter()
                }
                Box(Modifier.width(1.dp).fillMaxHeight().padding(vertical = 4.dp).background(pal.dim.copy(alpha = 0.3f)))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(0.6f).fillMaxHeight()) {
                    spectrumBlock()
                    waterfallBlock()
                }
            }
        } else {
            Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 14.dp, vertical = 10.dp)) {
                headerBlock()
                spectrumBlock()
                waterfallBlock()
                controlsFooter()
            }
        }


        if (showSettings) {
            Box(Modifier.fillMaxSize().background(Color(0xAA000000))
                .clickable(remember { MutableInteractionSource() }, null) { showSettings = false })
            Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(pal.panel)
                .clickable(remember { MutableInteractionSource() }, null) { }
                .navigationBarsPadding().padding(horizontal = 14.dp, vertical = 12.dp)) {
                Row(Modifier.fillMaxWidth().padding(bottom = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    Label("SETTINGS", 12, pal.text)
                    Text("CLOSE", color = pal.accent, fontFamily = Mono, fontSize = 12.sp,
                        modifier = Modifier.clickable(remember { MutableInteractionSource() }, null) { showSettings = false })
                }
                Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState())) {
                    if (radio) {
                    Section("AUDIO")
                    SliderRow(if (muted) "MUTE" else "VOL", volume, 0f..1f,
                        onLabel = { muted = !muted; engine.demod.muted = muted },
                        onChange = { volume = it; engine.demod.volume = it; if (muted) { muted = false; engine.demod.muted = false } })
                    SliderRow(if (sql <= Demodulator.SQL_OFF) "SQL OFF" else "SQL %.0f".format(sql), sql, Demodulator.SQL_OFF..-10f,
                        onLabel = { sql = Demodulator.SQL_OFF; engine.demod.squelchDb = sql },
                        onChange = { sql = it; engine.demod.squelchDb = it },
                        trailing = "%.0f dB".format(engine.level))
                    }

                    Section("RECEIVER")
                    if (radio) Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("RATE", color = pal.text, fontFamily = Mono, fontSize = 11.sp, modifier = Modifier.width(72.dp))
                        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            SdrEngine.RATES.forEach { r ->
                                val sel = r == engine.sampleRate
                                Text("%.2f".format(Locale.US, r / 1e6), color = if (sel) Color.Black else pal.dim, fontFamily = Mono,
                                    fontSize = 11.sp, textAlign = TextAlign.Center,
                                    modifier = Modifier.weight(1f).background(if (sel) pal.accent else pal.dim.copy(alpha = 0.18f))
                                        .clickable(remember { MutableInteractionSource() }, null) { engine.applySampleRate(r) }
                                        .padding(vertical = 7.dp))
                            }
                        }
                        Text("MS/s", color = pal.dim, fontFamily = Mono, fontSize = 10.sp, textAlign = TextAlign.End, modifier = Modifier.width(40.dp))
                    }
                    if (radio) SliderRow("BW " + fmtBw(bw), bw.coerceIn(mode.minBw, mode.maxBw), mode.minBw..mode.maxBw,
                        onLabel = { bw = mode.defBw; engine.demod.bandwidth = mode.defBw },
                        onChange = { bw = it; engine.demod.bandwidth = it })
                    SliderRow("GAIN", engine.gain, 0f..49.6f,
                        onLabel = { engine.auto = !engine.auto; engine.setGain((engine.gain * 10).toInt(), engine.auto) },
                        onChange = { engine.gain = it; engine.auto = false; engine.setGain((it * 10).toInt(), false) },
                        trailing = if (engine.auto) "AUTO" else "%.1f dB".format(Locale.US, engine.gain))
                    ToggleRow("AUTO GAIN (AGC)", engine.auto) {
                        engine.auto = !engine.auto; engine.setGain((engine.gain * 10).toInt(), engine.auto)
                    }
                    SliderRow("PPM ${engine.ppm}", engine.ppm.toFloat(), -100f..100f,
                        onLabel = { engine.ppm = 0; engine.sendPpm(0) },
                        onChange = { val v = it.roundToInt(); if (v != engine.ppm) { engine.ppm = v; engine.sendPpm(v) } })
                    ToggleRow("OFFSET TUNING", engine.offsetTuning) {
                        engine.offsetTuning = !engine.offsetTuning; engine.sendOffsetTuning(engine.offsetTuning)
                    }
                    if (radio) ToggleRow("HF DIRECT SAMPLING (Q)", engine.direct) {
                        engine.direct = !engine.direct; engine.sendDirect(engine.direct)
                        if (!engine.direct && engine.khz < MIN_KHZ) change(MIN_KHZ)
                    }

                    Section("DISPLAY")
                    SliderRow("WF /${engine.wfDiv}", engine.wfDiv.toFloat(), 1f..8f,
                        onLabel = { engine.wfDiv = 1 },
                        onChange = { engine.wfDiv = it.roundToInt().coerceIn(1, 8) })
                    SliderRow("LVL " + "%+.0f".format(Locale.US, engine.shift), engine.shift, -20f..20f,
                        onLabel = { engine.shift = 0f },
                        onChange = { engine.shift = it })
                    ToggleRow("SPECTRUM FILL", engine.fill) { engine.fill = !engine.fill }
                    ToggleRow("PEAK MARKERS", engine.showPeaks) { engine.showPeaks = !engine.showPeaks }
                    ToggleRow("PEAK HOLD LINE", engine.showHold) { engine.showHold = !engine.showHold }
                    ToggleRow("BAND PLAN OVERLAY", engine.showBands) { engine.showBands = !engine.showBands }

                    Section("THEME")
                    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Palettes.all.forEach { p ->
                            val sel = p === engine.palette
                            Column(Modifier.weight(1f)
                                .background(p.panel)
                                .border(1.dp, if (sel) p.accent else Color.Transparent)
                                .clickable(remember { MutableInteractionSource() }, null) { engine.palette = p },
                                horizontalAlignment = Alignment.CenterHorizontally) {
                                Box(Modifier.fillMaxWidth().height(5.dp).background(p.accent))
                                Text(p.name, color = if (sel) p.text else p.dim, fontFamily = Mono, fontSize = 9.sp,
                                    maxLines = 1, modifier = Modifier.padding(vertical = 8.dp))
                            }
                        }
                    }

                    Section("GSM")
                    ToggleRow("IMSI NEW-ID BEEP", !engine.imsiBeepMuted) { engine.imsiBeepMuted = !engine.imsiBeepMuted }
                    Text("SEND GSMTAP TO (IP or IP:port, default 127.0.0.1)",
                        color = pal.dim, fontFamily = Mono, fontSize = 10.sp, modifier = Modifier.padding(top = 8.dp, bottom = 2.dp))
                    run {
                        val gsmPrefs = remember { ctx.getSharedPreferences("gsm", android.content.Context.MODE_PRIVATE) }
                        var text by remember { mutableStateOf(engine.gsmtapHost) }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            OutlinedTextField(text, { text = it }, singleLine = true,
                                textStyle = TextStyle(fontFamily = Mono, fontSize = 14.sp, color = pal.text),
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                                modifier = Modifier.weight(1f))
                            TextButton({
                                engine.gsmtapHost = text
                                gsmPrefs.edit().putString("gsmtap", engine.gsmtapHost).apply()
                            }) { Text("SET", color = pal.accent, fontFamily = Mono) }
                        }
                        Text("127.0.0.1 = this phone only, 255.255.255.255 = everyone on the network. Port defaults to 4729.",
                            color = pal.dim, fontFamily = Mono, fontSize = 9.sp)
                    }
                    Text("OPENCELLID API KEY (for the GSM cell locator)",
                        color = pal.dim, fontFamily = Mono, fontSize = 10.sp, modifier = Modifier.padding(top = 10.dp, bottom = 2.dp))
                    run {
                        val gsmPrefs = remember { ctx.getSharedPreferences("gsm", android.content.Context.MODE_PRIVATE) }
                        var text by remember { mutableStateOf(gsmPrefs.getString("ocid", "") ?: "") }
                        LaunchedEffect(Unit) { engine.ocidKey = text }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            OutlinedTextField(text, { text = it }, singleLine = true,
                                textStyle = TextStyle(fontFamily = Mono, fontSize = 14.sp, color = pal.text),
                                modifier = Modifier.weight(1f))
                            TextButton({
                                engine.ocidKey = text
                                gsmPrefs.edit().putString("ocid", text).apply()
                            }) { Text("SET", color = pal.accent, fontFamily = Mono) }
                        }
                        Text("free key from opencellid.org/register; tap a row in the GSM screen's IMSI table to locate its cell.",
                            color = pal.dim, fontFamily = Mono, fontSize = 9.sp)
                    }
                    Section("RECORD")
                    Row(Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        RecButton("AUDIO", rec.audioOn, rec.audioSince, Modifier.weight(1f)) {
                            if (rec.audioOn) rec.stopAudio()
                            else rec.startAudio("audio_${"%.3f".format(Locale.US, khz / 1000.0)}MHz_${mode.label}_${stamp()}.wav")
                        }
                        RecButton("IQ", rec.iqOn, rec.iqSince, Modifier.weight(1f)) {
                            if (rec.iqOn) rec.stopIq()
                            else {
                                if (engine.auto) Toast.makeText(ctx, "AGC is on - use a fixed gain for clean IQ (URH)", Toast.LENGTH_LONG).show()
                                rec.startIq("iq_${"%.3f".format(Locale.US, khz / 1000.0)}MHz_${engine.sampleRate}sps_${stamp()}.cu8")
                            }
                        }
                    }
                }
            }
        }
    }

    if (typing) {
        var text by remember { mutableStateOf("%.3f".format(Locale.US, khz / 1000.0)) }
        AlertDialog(
            onDismissRequest = { typing = false },
            containerColor = pal.panel,
            title = { Label("Frequency (MHz)", 14, pal.text) },
            text = {
                OutlinedTextField(text, { text = it }, singleLine = true,
                    textStyle = TextStyle(fontFamily = Mono, fontSize = 20.sp, color = pal.text),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
            },
            confirmButton = {
                TextButton({
                    text.replace(',', '.').toDoubleOrNull()?.let { change(Math.round(it * 1000.0)) }
                    typing = false
                }) { Text("SET", color = pal.accent, fontFamily = Mono) }
            },
            dismissButton = { TextButton({ typing = false }) { Text("CANCEL", color = pal.dim, fontFamily = Mono) } }
        )
    }
}

/** Terminal-style status:  $ RTL2832U · 1.92 MS/s · LIVE */
@Composable
private fun StatusLine(engine: SdrEngine, onTap: () -> Unit) {
    val pal = LocalPal.current
    var cursor by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) { while (true) { delay(530); cursor = !cursor } }
    val st = engine.status
    val lagging = engine.live && System.currentTimeMillis() - engine.lastDropMs < 3000
    val state = when {
        engine.live -> if (lagging) "LIVE · LAG" else "LIVE"
        st.startsWith("found") -> "READY"
        st == "stopped" -> "STOPPED"
        st == "starting driver..." -> "STARTING DRIVER"
        st == "connecting..." -> "CONNECTING"
        st == "looking for device..." -> "SCANNING"
        st.startsWith("no RTL") -> "PLUG IN DONGLE"
        else -> st.uppercase()
    }
    val normal = engine.live || state in setOf("READY", "STOPPED", "STARTING DRIVER", "CONNECTING", "SCANNING")
    val rate = "%.2f MS/s".format(Locale.US, engine.sampleRate / 1e6)
    val text = if (engine.device != null || engine.live) "\$ RTL2832U · $rate · $state" else "\$ NO DEVICE · $state"
    val color = if (engine.live) pal.accent else if (normal) pal.dim else Red
    Text(text + if (cursor) " ▌" else "", color = color, fontFamily = Mono, fontSize = 11.sp,
        maxLines = 1, overflow = TextOverflow.Ellipsis,
        modifier = Modifier.fillMaxWidth().padding(top = 2.dp)
            .clickable(remember { MutableInteractionSource() }, null) { onTap() })
}

@Composable
private fun Label(t: String, size: Int = 14, color: Color = LocalPal.current.dim) =
    Text(t, color = color, fontFamily = Mono, fontSize = size.sp)

@Composable
private fun Section(title: String) {
    val pal = LocalPal.current
    Column(Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 2.dp)) {
        Text(title, color = pal.accent, fontFamily = Mono, fontSize = 10.sp)
        Spacer(Modifier.height(4.dp))
        Box(Modifier.fillMaxWidth().height(1.dp).background(pal.dim.copy(alpha = 0.35f)))
    }
}

@Composable
private fun ToggleRow(label: String, on: Boolean, onToggle: () -> Unit) {
    val pal = LocalPal.current
    Row(Modifier.fillMaxWidth().clickable(remember { MutableInteractionSource() }, null) { onToggle() }.padding(vertical = 9.dp),
        horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = pal.text, fontFamily = Mono, fontSize = 11.sp)
        Text(if (on) "ON" else "OFF", color = if (on) Color.Black else pal.dim, fontFamily = Mono, fontSize = 11.sp,
            modifier = Modifier.background(if (on) pal.accent else pal.dim.copy(alpha = 0.18f)).padding(horizontal = 10.dp, vertical = 3.dp))
    }
}

@Composable
private fun SliderRow(
    label: String, value: Float, range: ClosedFloatingPointRange<Float>,
    onLabel: () -> Unit, onChange: (Float) -> Unit, trailing: String? = null
) {
    val pal = LocalPal.current
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = pal.text, fontFamily = Mono, fontSize = 11.sp,
            modifier = Modifier.width(72.dp).clickable(remember { MutableInteractionSource() }, null) { onLabel() })
        Slider(value, onChange, valueRange = range, modifier = Modifier.weight(1f).height(30.dp),
            colors = SliderDefaults.colors(thumbColor = pal.accent, activeTrackColor = pal.accent,
                inactiveTrackColor = pal.dim.copy(alpha = 0.3f)))
        if (trailing != null) Text(trailing, color = pal.dim, fontFamily = Mono, fontSize = 10.sp,
            textAlign = TextAlign.End, modifier = Modifier.width(52.dp))
    }
}

@Composable
private fun RecButton(label: String, on: Boolean, since: Long, modifier: Modifier, onClick: () -> Unit) {
    val pal = LocalPal.current
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(on) { while (on) { now = System.currentTimeMillis(); delay(500) } }
    val sec = if (on) ((now - since) / 1000).coerceAtLeast(0) else 0
    Text(if (on) "● REC $label %d:%02d".format(sec / 60, sec % 60) else "○ REC $label",
        color = if (on) Red else pal.dim, fontFamily = Mono, fontSize = 12.sp, textAlign = TextAlign.Center,
        modifier = modifier.background(pal.dim.copy(alpha = 0.15f))
            .clickable(remember { MutableInteractionSource() }, null) { onClick() }
            .padding(vertical = 9.dp))
}

@Composable
private fun BarButton(label: String, color: Color, modifier: Modifier, onClick: () -> Unit) {
    val pal = LocalPal.current
    Box(modifier.height(44.dp).background(pal.panel)
        .clickable(remember { MutableInteractionSource() }, null) { onClick() }, contentAlignment = Alignment.Center) {
        Text(label, color = color, fontFamily = Mono, fontSize = 14.sp, textAlign = TextAlign.Center)
    }
}

/** Simple gear icon drawn with a ring and 8 teeth. */
@Composable
private fun GearIcon(color: Color) {
    Canvas(Modifier.size(22.dp)) {
        val r = size.minDimension / 2
        val c = center
        drawCircle(color, r * 0.52f, c, style = Stroke(r * 0.28f))
        for (i in 0 until 8) {
            val a = i * PI / 4
            val dx = cos(a).toFloat(); val dy = sin(a).toFloat()
            drawLine(color, Offset(c.x + dx * r * 0.62f, c.y + dy * r * 0.62f),
                Offset(c.x + dx * r * 0.96f, c.y + dy * r * 0.96f), strokeWidth = r * 0.3f)
        }
    }
}

/** ADS-B screen: aircraft map or list, switchable. */
@Composable
private fun AdsbPanel(engine: SdrEngine, modifier: Modifier) {
    val pal = LocalPal.current
    val list = engine.aircraft
    val now = System.currentTimeMillis()
    val selected = remember { mutableStateOf<Int?>(null) }
    val selAc = list.firstOrNull { it.icao == selected.value }
    Column(modifier) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("AIRCRAFT ${list.size}  ·  MSGS ${engine.adsbMsgs}", color = pal.accent, fontFamily = Mono, fontSize = 12.sp,
                modifier = Modifier.weight(1f))
            listOf(true to "MAP", false to "LIST").forEach { (isMap, name) ->
                val sel = engine.adsbMap == isMap
                Text(name, color = if (sel) Color.Black else pal.dim, fontFamily = Mono, fontSize = 11.sp,
                    modifier = Modifier.padding(start = 6.dp)
                        .background(if (sel) pal.accent else pal.dim.copy(alpha = 0.18f))
                        .clickable(remember { MutableInteractionSource() }, null) { engine.adsbMap = isMap }
                        .padding(horizontal = 10.dp, vertical = 4.dp))
            }
        }
        Spacer(Modifier.height(6.dp))
        if (selAc != null) {
            AircraftDetail(selAc, now, pal) { selected.value = null }
            Spacer(Modifier.height(6.dp))
        }
        if (engine.adsbMap) {
            if (list.none { it.lat != null && it.lon != null }) {
                Text(if (engine.live) "waiting for aircraft positions..." else "press START to listen",
                    color = pal.dim, fontFamily = Mono, fontSize = 11.sp, modifier = Modifier.padding(bottom = 4.dp))
            }
            AdsbMap(list, engine.live, selected.value, { selected.value = it }, Modifier.weight(1f).fillMaxWidth())
        } else {
            Text("ICAO   CALLSGN    ALT   KT  HDG SQK  AGE", color = pal.dim, fontFamily = Mono, fontSize = 10.sp)
            Box(Modifier.fillMaxWidth().padding(vertical = 3.dp).height(1.dp).background(pal.dim.copy(alpha = 0.35f)))
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                list.forEach { a ->
                    val row = String.format(Locale.US, "%06X %-8s %6s %4s %4s %4s %3ds", a.icao,
                        a.callsign.ifEmpty { "--" }, a.alt?.toString() ?: "--", a.speed?.toString() ?: "--",
                        a.heading?.toString() ?: "--", a.squawk ?: "--", ((now - a.lastMs) / 1000).coerceAtLeast(0))
                    Column(Modifier.fillMaxWidth().clickable(remember { MutableInteractionSource() }, null) { selected.value = a.icao }) {
                        Text(row, color = if (a.icao == selected.value) pal.accent else pal.text, fontFamily = Mono, fontSize = 11.sp, maxLines = 1)
                        if (a.lat != null && a.lon != null)
                            Text(String.format(Locale.US, "       %.4f, %.4f", a.lat, a.lon), color = pal.dim, fontFamily = Mono, fontSize = 9.sp)
                    }
                }
            }
        }
    }
}

/** Tap-to-open detail panel: current values plus a short history (snapshots taken whenever callsign, altitude,
    speed, heading or squawk changes) with a small altitude sparkline. */
@Composable
private fun AircraftDetail(a: Aircraft, now: Long, pal: Palette, onClose: () -> Unit) {
    Column(Modifier.fillMaxWidth().background(pal.panel).border(1.dp, pal.grid).padding(10.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("%06X".format(a.icao) + (if (a.callsign.isNotEmpty()) "  " + a.callsign else ""),
                color = pal.accent, fontFamily = Mono, fontSize = 13.sp, modifier = Modifier.weight(1f))
            Text("✕", color = pal.dim, fontFamily = Mono, fontSize = 14.sp,
                modifier = Modifier.clickable(remember { MutableInteractionSource() }, null, onClick = onClose).padding(4.dp))
        }
        Text(String.format(Locale.US, "ALT %s ft   SPD %s kt   HDG %s°   SQUAWK %s   seen %ds ago (%d msgs)",
                a.alt?.toString() ?: "--", a.speed?.toString() ?: "--", a.heading?.toString() ?: "--",
                a.squawk ?: "----", ((now - a.lastMs) / 1000).coerceAtLeast(0), a.msgs),
            color = pal.text, fontFamily = Mono, fontSize = 10.sp)
        if (a.lat != null && a.lon != null)
            Text(String.format(Locale.US, "%.4f, %.4f", a.lat, a.lon), color = pal.dim, fontFamily = Mono, fontSize = 10.sp)
        if (a.history.size >= 2) {
            Spacer(Modifier.height(4.dp))
            Text("ALTITUDE", color = pal.dim, fontFamily = Mono, fontSize = 9.sp)
            val alts = a.history.mapNotNull { it.alt?.toFloat() }
            if (alts.size >= 2) {
                Canvas(Modifier.fillMaxWidth().height(36.dp)) {
                    val lo = alts.min(); val hi = max(alts.max(), lo + 1f)
                    val path = Path()
                    alts.forEachIndexed { i, v ->
                        val x = size.width * i / (alts.size - 1)
                        val y = size.height - (v - lo) / (hi - lo) * size.height
                        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                    }
                    drawPath(path, pal.accent, style = Stroke(width = 1.5f))
                }
            }
        }
        if (a.history.isNotEmpty()) {
            Spacer(Modifier.height(4.dp))
            Text("HISTORY (newest first)", color = pal.dim, fontFamily = Mono, fontSize = 9.sp)
            a.history.asReversed().take(10).forEach { h ->
                Text(String.format(Locale.US, "-%3ds  alt %-6s spd %-5s hdg %-4s sqk %s",
                        ((now - h.ms) / 1000).coerceAtLeast(0), h.alt?.toString() ?: "--",
                        h.speed?.toString() ?: "--", h.heading?.toString() ?: "--", h.squawk ?: "--"),
                    color = pal.dim, fontFamily = Mono, fontSize = 9.sp, maxLines = 1)
            }
        }
    }
}

/**
 * Offline radar-style aircraft display: concentric range rings (not a square grid) centred on the view,
 * north up, auto-zoomed to fit every aircraft, with a proper airplane silhouette (not just an arrowhead),
 * callsign + flight level labels, and a trail behind each aircraft. (The receiver position is unknown, so
 * the view centres on the aircraft, not on you -- there's no "you are here" dot.) Tap an aircraft or its
 * label to select it; tap empty space to deselect.
 */
@Composable
private fun AdsbMap(list: List<Aircraft>, live: Boolean, selected: Int?, onSelect: (Int?) -> Unit, modifier: Modifier) {
    val pal = LocalPal.current
    val tm = rememberTextMeasurer()
    val labelStyle = TextStyle(color = pal.text, fontFamily = Mono, fontSize = 9.sp)
    val selLabelStyle = TextStyle(color = pal.accent, fontFamily = Mono, fontSize = 9.sp)
    val dimStyle = TextStyle(color = pal.dim, fontFamily = Mono, fontSize = 10.sp)
    val withPos = list.filter { it.lat != null && it.lon != null }

    Box(modifier.background(Color.Black)) {
        Canvas(Modifier.fillMaxSize().pointerInput(withPos) {
            detectTapGestures { tap ->
                // hit-test needs the same projection as the draw pass below; recomputed here from the
                // same inputs so it always matches what's actually on screen
                if (withPos.isEmpty()) { onSelect(null); return@detectTapGestures }
                val pts = ArrayList<Pair<Double, Double>>()
                for (a in withPos) { pts.addAll(a.trail); pts.add(a.lat!! to a.lon!!) }
                val minLat = pts.minOf { it.first }; val maxLat = pts.maxOf { it.first }
                val minLon = pts.minOf { it.second }; val maxLon = pts.maxOf { it.second }
                val midLat = (minLat + maxLat) / 2; val midLon = (minLon + maxLon) / 2
                val kmLat = 111.32; val kmLon = 111.32 * cos(Math.toRadians(midLat))
                val spanX = max((maxLon - minLon) * kmLon, 20.0); val spanY = max((maxLat - minLat) * kmLat, 20.0)
                val pad = 30f
                val scale = min((size.width - 2 * pad) / spanX, (size.height - 2 * pad) / spanY).toFloat()
                fun at(lat: Double, lon: Double) = Offset(
                    size.width / 2 + ((lon - midLon) * kmLon * scale).toFloat(),
                    size.height / 2 - ((lat - midLat) * kmLat * scale).toFloat())
                val hit = withPos.minByOrNull { (at(it.lat!!, it.lon!!) - tap).getDistance() }
                if (hit != null && (at(hit.lat!!, hit.lon!!) - tap).getDistance() < 60f) onSelect(hit.icao) else onSelect(null)
            }
        }) {
            val w = size.width; val h = size.height
            val cx = w / 2f; val cy = h / 2f
            val kmLat = 111.32
            val midLat: Double; val midLon: Double; val kmLon: Double; val scale: Float
            if (withPos.isNotEmpty()) {
                val pts = ArrayList<Pair<Double, Double>>()
                for (a in withPos) { pts.addAll(a.trail); pts.add(a.lat!! to a.lon!!) }
                val minLat = pts.minOf { it.first }; val maxLat = pts.maxOf { it.first }
                val minLon = pts.minOf { it.second }; val maxLon = pts.maxOf { it.second }
                midLat = (minLat + maxLat) / 2; midLon = (minLon + maxLon) / 2
                kmLon = 111.32 * cos(Math.toRadians(midLat))
                val spanX = max((maxLon - minLon) * kmLon, 20.0)
                val spanY = max((maxLat - minLat) * kmLat, 20.0)
                val pad = 30.dp.toPx()
                scale = min((w - 2 * pad) / spanX, (h - 2 * pad) / spanY).toFloat() // px per km
            } else {
                // no aircraft yet: draw the radar at a neutral ~300 km span instead of nothing, so the
                // rings are there the moment you open the panel, not just after the first catch
                midLat = 0.0; midLon = 0.0; kmLon = 111.32
                scale = w / 300f
            }
            fun at(lat: Double, lon: Double) =
                Offset(cx + ((lon - midLon) * kmLon * scale).toFloat(), cy - ((lat - midLat) * kmLat * scale).toFloat())

            // concentric range rings, spaced at a "nice" km step, centred on the view (not on a receiver
            // position, which is unknown) -- a radar look instead of a square grid
            val maxR = sqrt(cx * cx + cy * cy) * 0.85f // kept inset from the edges so the outer ring doesn't run under the surrounding UI
            val ringColor = lerp(pal.grid, Color.White, 0.15f) // 15% whiter than the base grid colour
            var ringStep = 500.0
            for (cand in listOf(5.0, 10.0, 20.0, 50.0, 100.0, 200.0, 500.0)) {
                if (cand * scale >= 40.dp.toPx()) { ringStep = cand; break }
            }
            var ring = ringStep
            while (ring * scale < maxR) {
                drawCircle(ringColor, radius = (ring * scale).toFloat(), center = Offset(cx, cy), style = Stroke(width = 1f))
                ring += ringStep
            }
            // crosshair + compass ticks at N/E/S/W (same colour as the range rings, so they read as one grid)
            drawLine(ringColor, Offset(0f, cy), Offset(w, cy))
            drawLine(ringColor, Offset(cx, 0f), Offset(cx, h))
            drawText(tm, "N", Offset(cx - 4.dp.toPx(), 4.dp.toPx()), dimStyle)
            drawText(tm, "${ringStep.toInt()} km / ring", Offset(10f, h - 16.dp.toPx()), dimStyle)

            for (a in withPos) {
                val p = at(a.lat!!, a.lon!!)
                val isSel = a.icao == selected
                if (a.trail.size >= 2) {
                    val path = Path()
                    a.trail.forEachIndexed { i, t -> val o = at(t.first, t.second); if (i == 0) path.moveTo(o.x, o.y) else path.lineTo(o.x, o.y) }
                    path.lineTo(p.x, p.y)
                    drawPath(path, if (isSel) pal.accent else pal.accent.copy(alpha = 0.45f), style = Stroke(width = if (isSel) 2.5f else 1.5f))
                }
                if (isSel) drawCircle(pal.accent.copy(alpha = 0.22f), radius = 16.dp.toPx(), center = p)
                drawAirplaneIcon(p, (a.heading ?: 0).toDouble(), if (isSel) pal.accent else if (a.heading != null) pal.text else pal.dim,
                    scale = if (isSel) 1.35f else 1f)
                val name = a.callsign.ifEmpty { "%06X".format(a.icao) }
                val fl = a.alt?.let { " FL%03d".format(it / 100) } ?: ""
                drawText(tm, name + fl, Offset(p.x + 11.dp.toPx(), p.y - 6.dp.toPx()), if (isSel) selLabelStyle else labelStyle)
            }
        }
    }
}

/** A simple, clean top-down airplane silhouette (one closed outline: nose, swept wings, tail with
    horizontal stabilizers), pointing along [headingDeg] (0 = north). The previous version built this out
    of three separately-filled, overlapping triangle paths (fuselage/wings/tailplane), which didn't line
    up cleanly and looked broken -- this is one continuous outline instead. */
private fun DrawScope.drawAirplaneIcon(center: Offset, headingDeg: Double, color: Color, scale: Float = 1f) {
    val th = Math.toRadians(headingDeg)
    val c = cos(th).toFloat(); val sn = sin(th).toFloat()
    val s = (1.1f.dp.toPx()) * scale // px per local unit (local shape spans roughly -10..10)
    fun rot(x: Float, y: Float) = Offset(center.x + (x * c - y * sn) * s, center.y + (x * sn + y * c) * s)
    val plane = Path().apply {
        val nose = rot(0f, -10f); moveTo(nose.x, nose.y)
        val noseR = rot(1f, -6f); lineTo(noseR.x, noseR.y)
        val wingTipR = rot(9f, 2f); lineTo(wingTipR.x, wingTipR.y)
        val wingInR = rot(2f, 1f); lineTo(wingInR.x, wingInR.y)
        val rearR = rot(2f, 7f); lineTo(rearR.x, rearR.y)
        val tailTipR = rot(5f, 9f); lineTo(tailTipR.x, tailTipR.y)
        val tailNotch = rot(0f, 7f); lineTo(tailNotch.x, tailNotch.y)
        val tailTipL = rot(-5f, 9f); lineTo(tailTipL.x, tailTipL.y)
        val rearL = rot(-2f, 7f); lineTo(rearL.x, rearL.y)
        val wingInL = rot(-2f, 1f); lineTo(wingInL.x, wingInL.y)
        val wingTipL = rot(-9f, 2f); lineTo(wingTipL.x, wingTipL.y)
        val noseL = rot(-1f, -6f); lineTo(noseL.x, noseL.y)
        close()
    }
    drawPath(plane, color)
}

private fun median(v: List<Float>): Float = if (v.isEmpty()) -100f else v.sorted()[v.size / 2]

/** GSM scanner: sweeps one band and lists the base-station beacon channels it finds. */
@Composable
private fun GsmPanel(engine: SdrEngine, modifier: Modifier) {
    val pal = LocalPal.current
    val res = engine.gsmResults
    val noise = median(res.map { it.powerDb })
    val beacons = res.filter { it.hits > 0 }.sortedByDescending { it.powerDb }
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val prefs = remember { ctx.getSharedPreferences("gsm", android.content.Context.MODE_PRIVATE) }
    var dlg by remember { mutableStateOf(0) } // 2 = live on a typed frequency (the Wireshark address lives in Settings now)
    LaunchedEffect(Unit) { engine.gsmtapHost = prefs.getString("gsmtap", "127.0.0.1:4729") ?: "127.0.0.1:4729" }
    if (dlg == 2) {
        var text by remember { mutableStateOf(prefs.getString("livefreq", "") ?: "") }
        AlertDialog(
            onDismissRequest = { dlg = 0 },
            containerColor = pal.panel,
            title = { Label("Live decode on frequency (MHz)", 14, pal.text) },
            text = {
                Column {
                    OutlinedTextField(text, { text = it }, singleLine = true,
                        textStyle = TextStyle(fontFamily = Mono, fontSize = 20.sp, color = pal.text),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                    Text("Beacon (BCCH) channel, e.g. 956.4. No scan needed; the frequency error is found automatically " +
                        "(can take a few seconds to lock).",
                        color = pal.dim, fontFamily = Mono, fontSize = 10.sp, modifier = Modifier.padding(top = 8.dp))
                }
            },
            confirmButton = {
                TextButton({
                    val mhz = text.replace(',', '.').toDoubleOrNull()
                    when {
                        mhz == null || mhz < 800.0 || mhz > 1000.0 ->
                            android.widget.Toast.makeText(ctx, "enter a GSM850/900 frequency in MHz, e.g. 956.4", android.widget.Toast.LENGTH_LONG).show()
                        !engine.live ->
                            android.widget.Toast.makeText(ctx, "press START first", android.widget.Toast.LENGTH_SHORT).show()
                        else -> {
                            prefs.edit().putString("livefreq", text).apply()
                            if (!engine.verifying) engine.startVerify(Math.round(mhz * 1000.0))
                            dlg = 0
                        }
                    }
                }) { Text("GO", color = pal.accent, fontFamily = Mono) }
            },
            dismissButton = { TextButton({ dlg = 0 }) { Text("CANCEL", color = pal.dim, fontFamily = Mono) } }
        )
    }
    val inFeed = engine.verifying || engine.verifyLog.isNotEmpty() // live-decode view is up, not the scan screen
    var locateRow by remember { mutableStateOf<ImsiRow?>(null) }
    Column(modifier) {
        if (!inFeed) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                GsmBand.values().forEach { b ->
                    val sel = b == engine.gsmBand
                    Text(b.label, color = if (sel) Color.Black else pal.dim, fontFamily = Mono, fontSize = 11.sp,
                        modifier = Modifier.background(if (sel) pal.accent else pal.dim.copy(alpha = 0.18f))
                            .clickable(remember { MutableInteractionSource() }, null) { if (!engine.gsmScanning) engine.gsmBand = b }
                            .padding(horizontal = 10.dp, vertical = 5.dp))
                }
                Spacer(Modifier.weight(1f))
                Text(if (engine.gsmScanning) "STOP" else "SCAN", color = if (engine.gsmScanning) Red else pal.accent,
                    fontFamily = Mono, fontSize = 12.sp,
                    modifier = Modifier.background(pal.dim.copy(alpha = 0.18f))
                        .clickable(remember { MutableInteractionSource() }, null) {
                            if (engine.gsmScanning) engine.stopGsmScan() else engine.startGsmScan()
                        }
                        .padding(horizontal = 14.dp, vertical = 5.dp))
            }
            Spacer(Modifier.height(6.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("LIVE ON FREQ", color = pal.accent, fontFamily = Mono, fontSize = 11.sp,
                    modifier = Modifier.background(pal.dim.copy(alpha = 0.18f))
                        .clickable(remember { MutableInteractionSource() }, null) { if (!engine.gsmScanning) dlg = 2 }
                        .padding(horizontal = 10.dp, vertical = 5.dp))
            }
            Spacer(Modifier.height(6.dp))
            Text(when {
                !engine.live -> "press START, then SCAN"
                engine.gsmProgress.isNotEmpty() -> engine.gsmProgress
                else -> "tap SCAN - a sweep takes about a minute"
            }, color = pal.dim, fontFamily = Mono, fontSize = 10.sp, maxLines = 1)
            Spacer(Modifier.height(6.dp))

            // power per channel; bright bars = beacon (FCCH found)
            val total = GsmBands.channels(engine.gsmBand).size
            Canvas(Modifier.fillMaxWidth().height(64.dp).background(pal.panel)) {
                if (res.isEmpty()) return@Canvas
                val lo = noise - 3f
                val hi = max(res.maxOf { it.powerDb }, lo + 10f)
                val bw = size.width / total
                res.forEachIndexed { i, r ->
                    val hgt = size.height * ((r.powerDb - lo) / (hi - lo)).coerceIn(0.02f, 1f)
                    drawRect(if (r.hits > 0) pal.accent else pal.dim.copy(alpha = 0.55f),
                        Offset(i * bw, size.height - hgt), androidx.compose.ui.geometry.Size(max(bw - 0.5f, 1f), hgt))
                }
            }
            Spacer(Modifier.height(6.dp))
        }
        locateRow?.let { row ->
            CellLocatorDialog(engine, row) { locateRow = null }
        }
        if (inFeed) {
            // live feed, like grgsm_livemon's console: scrolls as raw bursts and decoded messages arrive
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(if (engine.verifying) "● live" else "stopped", color = if (engine.verifying) pal.accent else pal.dim,
                    fontFamily = Mono, fontSize = 11.sp, modifier = Modifier.weight(1f))
                GsmView.values().forEach { v ->
                    val sel = engine.gsmView == v
                    Text(v.label, color = if (sel) Color.Black else pal.dim, fontFamily = Mono, fontSize = 11.sp,
                        modifier = Modifier.padding(start = 6.dp)
                            .background(if (sel) pal.accent else pal.dim.copy(alpha = 0.18f))
                            .clickable(remember { MutableInteractionSource() }, null) { engine.gsmView = v }
                            .padding(horizontal = 10.dp, vertical = 4.dp))
                }
                Text(if (engine.verifying) "STOP" else "BACK TO SCAN", color = if (engine.verifying) Red else pal.accent,
                    fontFamily = Mono, fontSize = 11.sp,
                    modifier = Modifier.padding(start = 10.dp).clickable(remember { MutableInteractionSource() }, null) {
                        if (engine.verifying) engine.stopVerify() else engine.verifyLog = emptyList()
                    })
            }
            if (engine.gsmView == GsmView.PKT) {
                val rec = engine.recorder
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("decoded L3 messages, Wireshark-style (same frames sent as GSMTAP)",
                        color = pal.dim.copy(alpha = 0.6f), fontFamily = Mono, fontSize = 9.sp, modifier = Modifier.weight(1f))
                    if (rec != null) {
                        Text(if (rec.pcapOn) "● REC .PCAP" else "RECORD .PCAP",
                            color = if (rec.pcapOn) Red else pal.accent, fontFamily = Mono, fontSize = 10.sp,
                            modifier = Modifier.clickable(remember { MutableInteractionSource() }, null) {
                                if (rec.pcapOn) rec.stopPcap()
                                else rec.startPcap("gsm_${engine.verifyArfcn}_${stamp()}.pcap")
                            })
                    }
                }
            }
            when (engine.gsmView) {
                GsmView.HEX -> {
                    Text("RTL-SDR → GMSK demod → burst detection → deinterleave → Viterbi → Fire CRC → L3 message",
                        color = pal.dim.copy(alpha = 0.6f), fontFamily = Mono, fontSize = 9.sp)
                    Spacer(Modifier.height(4.dp))
                    Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                        if (engine.verifyLog.isEmpty()) {
                            Text(if (engine.verifying) "waiting for a burst to sync..." else "nothing seen",
                                color = pal.dim, fontFamily = Mono, fontSize = 11.sp)
                        }
                        engine.verifyLog.forEach { line ->
                            val color = when (line.kind) { 2 -> pal.accent; 1 -> Color(0xFFFFB000); else -> pal.dim }
                            Text(line.text, color = color, fontFamily = Mono, fontSize = 10.sp, maxLines = 1)
                        }
                    }
                }
                GsmView.IMSI -> {
                    Text("catches IMSI/TMSI from paging on this channel; MCC/MNC/LAC/CI are the cell you're on (SI3)",
                        color = pal.dim.copy(alpha = 0.6f), fontFamily = Mono, fontSize = 9.sp)
                    Spacer(Modifier.height(4.dp))
                    Column(Modifier.weight(1f).horizontalScroll(rememberScrollState())) {
                        Text("ID ; IMSI            ; TMSI       ; MCC ; MNC ; LAC   ; CI   (tap a row to locate its cell)",
                            color = pal.dim, fontFamily = Mono, fontSize = 10.sp, maxLines = 1)
                        Box(Modifier.fillMaxWidth().padding(vertical = 3.dp).height(1.dp).background(pal.dim.copy(alpha = 0.35f)))
                        Column(Modifier.verticalScroll(rememberScrollState())) {
                            if (engine.imsiRows.isEmpty()) {
                                Text(if (engine.verifying) "waiting for a paging message..." else "nothing seen",
                                    color = pal.dim, fontFamily = Mono, fontSize = 11.sp)
                            }
                            engine.imsiRows.forEach { row ->
                                Text(String.format(Locale.US, "%-3d; %-16s; %-11s; %-4s; %-4s; %-6s; %-5s",
                                        row.id, row.imsi, row.tmsi, row.mcc, row.mnc, row.lac, row.cell),
                                    color = pal.accent, fontFamily = Mono, fontSize = 10.sp, maxLines = 1,
                                    modifier = Modifier.clickable(remember { MutableInteractionSource() }, null) { locateRow = row })
                            }
                        }
                    }
                }
                GsmView.PKT -> {
                    Column(Modifier.weight(1f).horizontalScroll(rememberScrollState())) {
                        Text("No.  Time      ARFCN Channel Len  Message",
                            color = pal.dim, fontFamily = Mono, fontSize = 10.sp, maxLines = 1)
                        Box(Modifier.fillMaxWidth().padding(vertical = 3.dp).height(1.dp).background(pal.dim.copy(alpha = 0.35f)))
                        Column(Modifier.verticalScroll(rememberScrollState())) {
                            if (engine.gsmPackets.isEmpty()) {
                                Text(if (engine.verifying) "waiting for a verified message..." else "nothing seen",
                                    color = pal.dim, fontFamily = Mono, fontSize = 11.sp)
                            }
                            engine.gsmPackets.forEach { p ->
                                val t = java.text.SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(java.util.Date(p.atMs))
                                Text(String.format(Locale.US, "%-4d %s %5d %-7s %-4d %s",
                                        p.no, t, p.arfcn, p.channel, p.length, p.type),
                                    color = if (p.channel == "BCCH") pal.accent else pal.text, fontFamily = Mono, fontSize = 10.sp, maxLines = 1)
                            }
                        }
                    }
                }
            }
            return@Column
        }
        Text("ARFCN    MHz     dB   ERR kHz  FCCH  (tap a row to verify)", color = pal.dim, fontFamily = Mono, fontSize = 10.sp)
        Box(Modifier.fillMaxWidth().padding(vertical = 3.dp).height(1.dp).background(pal.dim.copy(alpha = 0.35f)))
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            if (res.isNotEmpty() && beacons.isEmpty()) {
                Text("no beacon channels found yet", color = pal.dim, fontFamily = Mono, fontSize = 11.sp)
            }
            beacons.forEach { r ->
                Text(String.format(Locale.US, "%5d %8.1f %6.1f %+8.1f %5d", r.arfcn, r.khz / 1000.0, r.powerDb, r.offsetHz / 1000f, r.hits),
                    color = pal.accent, fontFamily = Mono, fontSize = 11.sp, maxLines = 1,
                    modifier = Modifier.clickable(remember { MutableInteractionSource() }, null) {
                        if (engine.live && !engine.verifying) engine.startVerify(r.khz)
                    })
            }
            Text("passive scan of base-station beacons only. Tap a row to watch that channel live, the " +
                "way grgsm_livemon does, until you hit STOP.",
                color = pal.dim.copy(alpha = 0.6f), fontFamily = Mono,
                fontSize = 9.sp, modifier = Modifier.padding(top = 10.dp))
        }
    }
}


/** A dark-tile (CartoDB "Dark Matter") Leaflet map centred on ([lat],[lon]). Loaded as inline HTML into a
    plain WebView -- no map SDK/API key needed, just network access to unpkg.com (Leaflet itself) and
    basemaps.cartocdn.com (the tiles); if either is unreachable the WebView just stays on its dark
    background colour (set below) instead of showing anything broken. [onResult] reports true once the
    page has actually finished loading, false if the WebView reports a load error, so the caller can show
    something else (or just rely on [CellLocatorDot] drawn on top) when the tiles never arrive. Rebuilt
    (not just panned) whenever the fix changes, which is fine since it only changes on a fresh lookup. */
@Composable
private fun CellDarkMap(lat: Double, lon: Double, rangeM: Int, accent: Color, modifier: Modifier, onResult: (Boolean) -> Unit) {
    val zoom = when { rangeM < 300 -> 16; rangeM < 800 -> 15; rangeM < 2000 -> 13; rangeM < 6000 -> 11; else -> 9 }
    val hex = String.format("#%06X", 0xFFFFFF and accent.toArgb())
    val html = """
        <!DOCTYPE html><html><head>
        <meta name="viewport" content="width=device-width, initial-scale=1.0, user-scalable=no">
        <link rel="stylesheet" href="https://unpkg.com/leaflet@1.9.4/dist/leaflet.css"/>
        <style>html,body,#m{height:100%;width:100%;margin:0;padding:0;background:#0d0d0d;}</style>
        </head><body>
        <div id="m"></div>
        <script src="https://unpkg.com/leaflet@1.9.4/dist/leaflet.js"></script>
        <script>
          var map = L.map('m', {attributionControl:false}).setView([$lat, $lon], $zoom);
          L.tileLayer('https://{s}.basemaps.cartocdn.com/dark_all/{z}/{x}/{y}{r}.png', {
            subdomains: 'abcd', maxZoom: 19
          }).addTo(map);
          L.circle([$lat, $lon], {radius: ${rangeM.coerceAtLeast(30)}, color: '$hex', weight: 1, fillColor: '$hex', fillOpacity: 0.15}).addTo(map);
          L.circleMarker([$lat, $lon], {radius: 6, color: '$hex', weight: 2, fillColor: '$hex', fillOpacity: 1}).addTo(map);
          setTimeout(function(){ map.invalidateSize(); }, 50);
        </script>
        </body></html>
    """.trimIndent()
    key(lat, lon, rangeM) {
        AndroidView(modifier = modifier, factory = { ctx ->
            WebView(ctx).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.useWideViewPort = true
                settings.loadWithOverviewMode = true
                settings.cacheMode = WebSettings.LOAD_DEFAULT
                if (android.os.Build.VERSION.SDK_INT >= 21) settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                setLayerType(android.view.View.LAYER_TYPE_HARDWARE, null)
                setBackgroundColor(android.graphics.Color.parseColor("#0d0d0d"))
                webViewClient = object : android.webkit.WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) { onResult(true) }
                    @Suppress("DEPRECATION")
                    override fun onReceivedError(view: WebView?, errorCode: Int, description: String?, failingUrl: String?) {
                        onResult(false) // legacy (API<23) callback, but works on every version without an SDK gate
                    }
                }
                loadDataWithBaseURL("https://local/", html, "text/html", "UTF-8", null)
            }
        })
    }
}

/** The tower position as a self-contained drawing: concentric range rings, a crosshair and an accuracy
    circle + dot at the centre -- the same treatment [AdsbMap] uses for its range rings, so it always
    renders regardless of whether [CellDarkMap]'s tiles ever loaded. Drawn on top of the map (or alone, if
    the map didn't load), centred on the same point the map is centred on. */
@Composable
private fun CellLocatorDot(rangeM: Int, pal: Palette, modifier: Modifier) {
    Canvas(modifier) {
        val cx = size.width / 2f; val cy = size.height / 2f
        val maxR = sqrt(cx * cx + cy * cy) * 0.85f
        val ringColor = lerp(pal.grid, Color.White, 0.15f) // same treatment as AdsbMap's range rings
        val r = rangeM.coerceAtLeast(50)
        var ringStep = 50.0
        for (cand in listOf(50.0, 100.0, 200.0, 500.0, 1000.0, 2000.0, 5000.0)) {
            if (cand / r * maxR >= 40.dp.toPx()) { ringStep = cand; break }
        }
        val scale = maxR / (r * 2.2f) // px per metre, so the accuracy ring sits inside the view
        var ring = ringStep
        while (ring * scale < maxR) {
            drawCircle(ringColor, radius = (ring * scale).toFloat(), center = Offset(cx, cy), style = Stroke(width = 1f))
            ring += ringStep
        }
        drawLine(ringColor, Offset(0f, cy), Offset(size.width, cy))
        drawLine(ringColor, Offset(cx, 0f), Offset(cx, size.height))
        drawCircle(pal.accent.copy(alpha = 0.18f), radius = (r * scale), center = Offset(cx, cy))
        drawCircle(pal.accent, radius = 5.dp.toPx(), center = Offset(cx, cy))
    }
}

/**
 * GSM cell locator: resolves the serving cell (MCC/MNC/LAC/CI, decoded from SI3 by the IMSI catcher) to
 * a map position via OpenCellID. Shows a dark-themed map when its tiles load; the dot and accuracy
 * circle ([CellLocatorDot]) are drawn on top either way, so the position is always visible even with no
 * network access to the map tiles.
 */
@Composable
private fun CellLocatorDialog(engine: SdrEngine, row: ImsiRow, onDismiss: () -> Unit) {
    val pal = LocalPal.current
    val fix = engine.cellFix
    LaunchedEffect(row) { engine.lookupCell(row) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = pal.panel,
        title = { Label("Cell locator (OpenCellID)", 14, pal.text) },
        text = {
            Column(Modifier.fillMaxWidth()) {
                if (fix == null) {
                    Text(engine.cellLookupStatus.ifEmpty { "looking up..." }, color = pal.dim, fontFamily = Mono, fontSize = 11.sp)
                } else {
                    var mapOk by remember(fix) { mutableStateOf<Boolean?>(null) }
                    Box(Modifier.fillMaxWidth().height(260.dp).background(Color(0xFF0D0D0D))) {
                        CellDarkMap(fix.lat, fix.lon, fix.rangeM, pal.accent, Modifier.fillMaxSize()) { mapOk = it }
                        // always on top, whether or not the tiles above actually loaded
                        CellLocatorDot(fix.rangeM, pal, Modifier.fillMaxSize())
                        if (mapOk == false) {
                            Text("map tiles unavailable (no network?) - showing position only",
                                color = pal.dim, fontFamily = Mono, fontSize = 9.sp,
                                modifier = Modifier.align(Alignment.BottomStart).padding(6.dp))
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(String.format(Locale.US, "%.5f, %.5f   ±%d m", fix.lat, fix.lon, fix.rangeM),
                        color = pal.accent, fontFamily = Mono, fontSize = 12.sp)
                    Text("MCC ${fix.mcc}  MNC ${fix.mnc}  LAC ${fix.lac}  CI ${fix.cell}",
                        color = pal.dim, fontFamily = Mono, fontSize = 10.sp)
                }
                Spacer(Modifier.height(8.dp))
                Text("Position of the serving cell tower from OpenCellID's crowd-sourced database, not the phone's own GPS fix.",
                    color = pal.dim.copy(alpha = 0.7f), fontFamily = Mono, fontSize = 9.sp)
            }
        },
        confirmButton = { TextButton({ engine.lookupCell(row) }) { Text("REFRESH", color = pal.accent, fontFamily = Mono) } },
        dismissButton = { TextButton(onDismiss) { Text("CLOSE", color = pal.dim, fontFamily = Mono) } }
    )
}

/** NOAA APT weather-satellite image: live scrolling preview, built from passing bursts. No continuous line
    resync, so a long pass can skew; the most recent ~900 lines (~7.5 min) are kept and can be saved as a PNG. */
@Composable
private fun SatellitePanel(engine: SdrEngine, modifier: Modifier) {
    val pal = LocalPal.current
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val img = remember { engine.aptBitmap.asImageBitmap() }
    Column(modifier) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (engine.aptLocked) "LOCKED  ·  ${engine.aptLines} lines" else if (engine.live) "searching for sync..." else "not listening",
                color = if (engine.aptLocked) pal.accent else pal.dim, fontFamily = Mono, fontSize = 12.sp, modifier = Modifier.weight(1f)
            )
            Text("SAVE", color = pal.accent, fontFamily = Mono, fontSize = 11.sp,
                modifier = Modifier.background(pal.dim.copy(alpha = 0.18f))
                    .clickable(remember { MutableInteractionSource() }, null) {
                        saveAptImage(ctx, engine.aptBitmap)
                    }
                    .padding(horizontal = 10.dp, vertical = 4.dp))
        }
        Spacer(Modifier.height(6.dp))
        Text(
            "NOAA 15/18/19, ~137 MHz: tune to the satellite's exact frequency during a pass. " +
                "Free-running line clock after the first lock, so long passes can skew.",
            color = pal.dim, fontFamily = Mono, fontSize = 9.sp
        )
        Spacer(Modifier.height(6.dp))
        val f = engine.aptImageVersion
        Image(img, contentDescription = "APT image", modifier = Modifier.weight(1f).fillMaxWidth(),
            contentScale = androidx.compose.ui.layout.ContentScale.FillBounds)
    }
}

private fun saveAptImage(ctx: android.content.Context, bmp: android.graphics.Bitmap) {
    try {
        val name = "apt_${System.currentTimeMillis()}.png"
        val resolver = ctx.contentResolver
        val cv = android.content.ContentValues().apply {
            put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "image/png")
            put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/RtlSdr")
        }
        val uri = resolver.insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv)
        if (uri != null) {
            resolver.openOutputStream(uri)?.use { bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            android.widget.Toast.makeText(ctx, "saved to Pictures/RtlSdr/$name", android.widget.Toast.LENGTH_LONG).show()
        }
    } catch (e: Exception) {
        android.widget.Toast.makeText(ctx, "save failed: ${e.message}", android.widget.Toast.LENGTH_LONG).show()
    }
}
