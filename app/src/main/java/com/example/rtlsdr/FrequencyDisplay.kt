package com.example.rtlsdr

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp

private val GLYPHS: Map<Char, List<String>> = mapOf(
    '0' to listOf("01110", "10001", "10011", "10101", "11001", "10001", "01110"),
    '1' to listOf("00100", "01100", "00100", "00100", "00100", "00100", "01110"),
    '2' to listOf("01110", "10001", "00001", "00010", "00100", "01000", "11111"),
    '3' to listOf("11110", "00001", "00001", "01110", "00001", "00001", "11110"),
    '4' to listOf("00010", "00110", "01010", "10010", "11111", "00010", "00010"),
    '5' to listOf("11111", "10000", "11110", "00001", "00001", "10001", "01110"),
    '6' to listOf("00110", "01000", "10000", "11110", "10001", "10001", "01110"),
    '7' to listOf("11111", "00001", "00010", "00100", "01000", "01000", "01000"),
    '8' to listOf("01110", "10001", "10001", "01110", "10001", "10001", "01110"),
    '9' to listOf("01110", "10001", "10001", "01111", "00001", "00010", "01100"),
    '.' to listOf("00", "00", "00", "00", "00", "11", "11"),
)

/**
 * One 5x7 LED-style cell grid: unlit cells are faintly visible, lit cells are solid.
 * While [t] < 1 the previous glyph [old] slides out and the new one slides in (up = value went up).
 */
@Composable
fun PixelGlyph(
    ch: Char, old: Char?, t: Float, up: Boolean, color: Color,
    modifier: Modifier = Modifier, cell: Float = 6f
) {
    val rows = GLYPHS[ch] ?: return
    val oldRows = if (old != null && t < 1f) GLYPHS[old] else null
    val cols = rows[0].length
    val margin = 3.5f
    Canvas(modifier.size((cols * cell + 2 * margin).dp, (7 * cell + 2 * margin).dp).clipToBounds()) {
        val c = cell.dp.toPx()
        val m = margin.dp.toPx()
        val h = 7 * c
        val cs = c - 1.5f

        // unlit cells (the "dead" LED segments)
        for (y in 0 until 7) for (x in 0 until cols)
            drawRect(color.copy(alpha = 0.07f), Offset(m + x * c, m + y * c), Size(cs, cs))

        fun lit(g: List<String>, yOff: Float) {
            g.forEachIndexed { y, row ->
                row.forEachIndexed { x, bit ->
                    if (bit == '1') {
                        val px = m + x * c
                        val py = m + y * c + yOff
                        drawRect(color, Offset(px, py), Size(cs, cs))
                    }
                }
            }
        }

        if (oldRows != null) lit(oldRows, if (up) -t * h else t * h)
        lit(rows, if (t >= 1f) 0f else if (up) (1f - t) * h else -(1f - t) * h)
    }
}

/** A digit that does a short vertical roll when its value changes. */
@Composable
private fun DigitCell(ch: Char, up: Boolean, color: Color, modifier: Modifier) {
    var shown by remember { mutableStateOf(ch) }
    var old by remember { mutableStateOf(ch) }
    val t = remember { Animatable(1f) }
    LaunchedEffect(ch) {
        if (ch != shown) {
            old = shown
            shown = ch
            t.snapTo(0f)
            t.animateTo(1f, tween(120))
        }
    }
    PixelGlyph(shown, old, t.value, up, color, modifier)
}

/**
 * Frequency in kHz shown as MHz (e.g. 0433.920).
 * Swipe up/down on a digit to change it (with a haptic tick), tap anywhere to type a value.
 */
@Composable
fun FrequencyDisplay(
    khz: Long, up: Boolean, color: Color, dimColor: Color,
    onDelta: (Long) -> Unit, onTap: () -> Unit
) {
    val s = khz.toString().padStart(7, '0')
    val delta = rememberUpdatedState(onDelta)
    val tap = rememberUpdatedState(onTap)
    val haptic = LocalHapticFeedback.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        s.forEachIndexed { i, ch ->
            if (i == 4) PixelGlyph('.', null, 1f, up, color)
            val place = Math.pow(10.0, (6 - i).toDouble()).toLong()
            val dim = i < 3 && s.substring(0..i).all { it == '0' }
            DigitCell(
                ch, up, if (dim) dimColor else color,
                Modifier
                    .pointerInput(Unit) { detectTapGestures(onTap = { tap.value() }) }
                    .pointerInput(place) {
                        var acc = 0f
                        val step = 22.dp.toPx()
                        detectVerticalDragGestures(onDragStart = { acc = 0f }) { change, dy ->
                            change.consume()
                            acc += dy
                            while (acc <= -step) { delta.value(place); haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove); acc += step }   // up = increase
                            while (acc >= step) { delta.value(-place); haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove); acc -= step }   // down = decrease
                        }
                    }
            )
        }
    }
}