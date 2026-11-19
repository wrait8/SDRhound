package com.example.rtlsdr

import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale
import kotlin.math.max

private class PendingImport(val uri: Uri, val name: String, val size: Long, val detectedKind: LrptFiles.Kind?)

/**
 * Meteor-M LRPT receiver panel: live status, the picture (colour composite or one channel), and offline decoding of recordings.
 * Live reception uses the radio (see [SdrEngine.lrpt]); IMPORT decodes a recorded file instead (WAV / raw IQ / soft symbols / CADU).
 */
@Composable
fun LrptPanel(engine: SdrEngine, accent: Color, dim: Color, text: Color, font: FontFamily, onSave: (android.graphics.Bitmap) -> Unit, modifier: Modifier = Modifier) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var pending by remember { mutableStateOf<PendingImport?>(null) }
    var rateText by remember { mutableStateOf("") }
    var notice by remember { mutableStateOf("") }
    val version = engine.lrptImageVersion // recompose whenever the picture or status changed

    var kindChoice by remember { mutableStateOf<LrptFiles.Kind?>(null) }
    fun start(p: PendingImport, rate: Int, kind: LrptFiles.Kind?) { pending = null; notice = ""; engine.importLrpt(ctx, p.uri, p.name, p.size, rate, kind) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            var name = "recording"; var size = -1L
            try { ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
                if (c.moveToFirst()) { val ni = c.getColumnIndex(OpenableColumns.DISPLAY_NAME); val si = c.getColumnIndex(OpenableColumns.SIZE)
                    if (ni >= 0) name = c.getString(ni) ?: name; if (si >= 0 && !c.isNull(si)) size = c.getLong(si) } } } catch (_: Exception) {}
            // Read the file's real first bytes so WAV (RIFF/WAVE) is recognised from its own header, not guessed from the
            // file name -- a WAV saved without a ".wav" extension was previously always misread as raw CU8.
            val head = try { ctx.contentResolver.openInputStream(uri)?.use { s -> val b = ByteArray(4096); val n = s.read(b); b.copyOf(maxOf(0, n)) } ?: ByteArray(0) } catch (_: Exception) { ByteArray(0) }
            val kind = LrptFiles.detect(name, head)
            val guessed = LrptFiles.guessRate(name)
            val p = PendingImport(uri, name, size, kind)
            kindChoice = kind
            // raw IQ: the format is only ever guessed from the file name/extension, which is often wrong (e.g. a 16-bit
            // recording saved as .raw/.bin/.iq/.cu8). Always let the user confirm or correct it, not just the rate.
            if (kind == null || kind.needsRate) { pending = p; rateText = guessed?.toString() ?: "" }
            else start(p, 0, kind)
        }
    }

    val small = TextStyle(fontFamily = font, fontSize = 11.sp, color = text)
    @Composable fun chip(label: String, on: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
        Text(label, color = if (on) Color.Black else if (enabled) accent else dim, fontFamily = font, fontSize = 11.sp,
            modifier = Modifier.background(if (on) accent else dim.copy(alpha = 0.18f))
                .clickable(remember { MutableInteractionSource() }, null) { if (enabled) onClick() }
                .padding(horizontal = 9.dp, vertical = 5.dp))
    }

    Column(modifier) {
        // ---------------------------------------------------------------- status
        val importing = engine.lrptImporting
        val headline = when {
            importing -> "DECODING FILE  %.0f%%".format(Locale.US, engine.lrptImportProgress * 100)
            engine.lrptLocked && engine.lrptGood > 0 -> "RECEIVING PICTURE"
            engine.lrptSignal -> "SIGNAL FOUND - SYNCING"
            engine.live -> "WAITING FOR A SIGNAL"
            else -> "RADIO OFF"
        }
        Column(Modifier.fillMaxWidth().background(if (engine.lrptLocked && engine.lrptGood > 0) accent.copy(alpha = 0.14f) else dim.copy(alpha = 0.10f))
            .border(1.dp, if (engine.lrptLocked && engine.lrptGood > 0) accent else dim.copy(alpha = 0.35f)).padding(10.dp)) {
            Text(headline, color = accent, fontFamily = font, fontSize = 14.sp)
            val q = engine.lrptQuality
            val line = if (importing) engine.lrptImportMsg else
                "frames ok ${engine.lrptGood}  ·  bad ${engine.lrptBad}  ·  picture ${engine.lrptRows} lines  ·  signal %.0f  ·  carrier %+.0f Hz".format(Locale.US, q, engine.lrptCarrier)
            Text(line, color = dim, fontFamily = font, fontSize = 10.sp)
            if (!importing && engine.lrptImportMsg.isNotEmpty()) Text(engine.lrptImportMsg, color = dim, fontFamily = font, fontSize = 10.sp)
            if (!engine.live && !importing) Text("Start the radio on a pass, or import a recording below.", color = dim, fontFamily = font, fontSize = 9.sp)
            Text("Meteor-M LRPT: 72 kbaud OQPSK on 137.9 / 137.1 MHz. (The 80 kbaud interleaved test mode isn't supported.)", color = dim, fontFamily = font, fontSize = 9.sp)
        }

        // ---------------------------------------------------------------- buttons
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            if (importing) chip("STOP") { engine.cancelLrptImport() }
            else chip("IMPORT FILE") { picker.launch(arrayOf("*/*")) }
            chip("SAVE", enabled = engine.lrptRows > 0) { engine.lrptSnapshot()?.let { onSave(it) } }
            chip("CLEAR", enabled = !importing) { engine.resetLrpt(); engine.lrptImportMsg = "" }
        }
        Text("Import: SatDump-style recordings - WAV IQ, raw .cu8 / .cs8 / .cs16 / .cf32, soft symbols (.soft / .s) or .cadu.", color = dim, fontFamily = font, fontSize = 9.sp,
            modifier = Modifier.padding(top = 3.dp))

        // sample rate prompt for raw IQ files whose name doesn't say
        pending?.let { p ->
            Spacer(Modifier.height(6.dp))
            Column(Modifier.fillMaxWidth().background(dim.copy(alpha = 0.12f)).padding(8.dp)) {
                Text("${p.name}: confirm the file format (auto-detected: ${p.detectedKind?.label ?: "unknown"})", style = small)
                Text("Picking the wrong one here silently decodes garbage -- it will often still look like a signal but never produce a picture.",
                    color = dim, fontFamily = font, fontSize = 9.sp, modifier = Modifier.padding(top = 2.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 5.dp)) {
                    for (k in listOf(LrptFiles.Kind.CU8, LrptFiles.Kind.CS8, LrptFiles.Kind.CS16, LrptFiles.Kind.CF32))
                        chip(k.label, on = kindChoice == k) { kindChoice = k }
                }
                if (kindChoice?.needsRate == true) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
                        Text("sample rate: ", style = small)
                        BasicTextField(rateText, { rateText = it.filter { c -> c.isDigit() } }, singleLine = true, textStyle = small, cursorBrush = SolidColor(accent),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.width(110.dp).background(dim.copy(alpha = 0.15f)).padding(6.dp))
                        Text(" Hz", style = small.copy(color = dim))
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 5.dp)) {
                        for (r in listOf(250_000, 1_024_000, 2_048_000, 2_400_000)) chip("%.3g M".format(Locale.US, r / 1e6)) { rateText = r.toString() }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 5.dp)) {
                    val r = rateText.toIntOrNull() ?: 0
                    val ready = kindChoice != null && (kindChoice?.needsRate != true || r >= 140_000)
                    chip("DECODE", enabled = ready) { start(p, r, kindChoice) }
                    chip("CANCEL") { pending = null }
                }
                if (kindChoice?.needsRate == true && (rateText.toIntOrNull() ?: 0) in 1..139_999) Text("needs at least 140000 Hz", color = dim, fontFamily = font, fontSize = 9.sp)
            }
        }

        // ---------------------------------------------------------------- picture
        val rows = engine.lrptRows
        if (rows > 0) {
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                chip("COLOUR", engine.lrptView == 0) { engine.selectLrptView(0) }
                for (a in engine.lrptApids) chip("CH${a - 63}", engine.lrptView == a) { engine.selectLrptView(a) }
            }
            Spacer(Modifier.height(6.dp))
            val bmp = engine.lrptBitmap
            Canvas(Modifier.fillMaxWidth().aspectRatio(Lrpt.WIDTH.toFloat() / max(rows, 8)).border(1.dp, dim.copy(alpha = 0.35f)).background(Color.Black)) {
                val shown = rows.coerceAtMost(bmp.height)
                drawImage(bmp.asImageBitmap(), srcOffset = IntOffset(0, 0), srcSize = IntSize(Lrpt.WIDTH, shown),
                    dstOffset = IntOffset(0, 0), dstSize = IntSize(size.width.toInt(), size.height.toInt()))
            }
            Text("COLOUR = channels 3/2/1 as red/green/blue. Newest lines are at the bottom; the picture is not rotated or map-corrected.", color = dim, fontFamily = font, fontSize = 9.sp,
                modifier = Modifier.padding(top = 3.dp))
        }
    }
}
