package com.example.rtlsdr

import androidx.compose.ui.graphics.Color

/** UI colours plus a 256-step waterfall colour table built from gradient stops (RGB hex). */
class Palette(
    val name: String,
    val accent: Color,
    val text: Color,
    val dim: Color,
    val panel: Color,
    val grid: Color,
    stops: List<Pair<Float, Long>>
) {
    val lut = IntArray(256) { i ->
        val t = i / 255f
        var k = 0
        while (k < stops.size - 2 && t > stops[k + 1].first) k++
        val (t0, c0) = stops[k]
        val (t1, c1) = stops[k + 1]
        val f = ((t - t0) / (t1 - t0)).coerceIn(0f, 1f)
        fun ch(shift: Int): Int {
            val a = ((c0 shr shift) and 0xFFL).toInt()
            val b = ((c1 shr shift) and 0xFFL).toInt()
            return (a + (b - a) * f).toInt()
        }
        (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }
}

object Palettes {
    val all = listOf(
        Palette("MONO", Color(0xFFE6E6E6), Color(0xFFFFFFFF), Color(0xFF808080), Color(0xFF141414), Color(0xFF1E1E1E),
            listOf(0f to 0x000000L, 0.6f to 0x808080L, 1f to 0xFFFFFFL)),
        Palette("GREEN", Color(0xFF00E676), Color(0xFFF2F2F2), Color(0xFF6E6E6E), Color(0xFF111111), Color(0xFF1A1A1A),
            listOf(0f to 0x000000L, 0.4f to 0x0000FFL, 0.75f to 0x00FFFFL, 1f to 0xFFFFFFL)),
        Palette("AMBER", Color(0xFFFFB000), Color(0xFFFFEBC2), Color(0xFF8A7A5A), Color(0xFF17130A), Color(0xFF1F1A0E),
            listOf(0f to 0x000000L, 0.3f to 0x4A1200L, 0.6f to 0xC23B00L, 0.85f to 0xFFB000L, 1f to 0xFFF3D0L)),
        Palette("ICE", Color(0xFF4FC3F7), Color(0xFFE3F4FF), Color(0xFF5E7A8A), Color(0xFF0C141A), Color(0xFF121C24),
            listOf(0f to 0x000000L, 0.35f to 0x0A1E5CL, 0.65f to 0x1E88E5L, 0.85f to 0x80DEEAL, 1f to 0xFFFFFFL)),
        Palette("NIGHT", Color(0xFFFF4D4D), Color(0xFFFFDADA), Color(0xFF7A5A5A), Color(0xFF1A0D0D), Color(0xFF241212),
            listOf(0f to 0x000000L, 0.35f to 0x400000L, 0.7f to 0xC00000L, 0.9f to 0xFF6060L, 1f to 0xFFE0E0L)),
        Palette("VIOLET", Color(0xFFB388FF), Color(0xFFF0E6FF), Color(0xFF7A6A8A), Color(0xFF140F1C), Color(0xFF1C1428),
            listOf(0f to 0x000000L, 0.35f to 0x2A0A5EL, 0.65f to 0x8E24AAL, 0.85f to 0xFF80ABL, 1f to 0xFFFFFFL))
    )
}