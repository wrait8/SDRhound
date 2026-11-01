package com.example.rtlsdr

import androidx.compose.ui.graphics.Color

/**
 * One labeled frequency allocation, drawn as a shaded band on the spectrum when the band-plan overlay
 * is on. These are general, approximate reference ranges (mostly US-centric where allocations differ
 * by country -- PMR446 vs FRS/GMRS, 433 MHz vs other ISM bands, etc.), meant as a quick "what am I
 * probably looking at" visual aid, not an authoritative table. This app only receives, and always check
 * your own country's actual allocation table before assuming what a given frequency is licensed for.
 */
class BandInfo(val name: String, val loKhz: Long, val hiKhz: Long, val color: Color)

object BandPlan {
    private val ham = Color(0xFF66BB6A)   // amateur radio
    private val mil = Color(0xFFEF5350)   // military / government
    private val pub = Color(0xFF42A5F5)   // aviation / marine / weather / public safety
    private val bcast = Color(0xFFFFCA28) // broadcast
    private val ism = Color(0xFFAB47BC)   // license-free / consumer (ISM, PMR, GMRS/FRS...)
    private val cell = Color(0xFF26C6DA)  // cellular
    private val nav = Color(0xFF8D6E63)   // satellite navigation

    val all = listOf(
        BandInfo("CB", 26_965, 27_405, ism),
        BandInfo("10m HAM", 28_000, 29_700, ham),
        BandInfo("6m HAM", 50_000, 54_000, ham),
        BandInfo("BCAST FM", 87_500, 108_000, bcast),
        BandInfo("AIR BAND", 118_000, 137_000, pub),
        BandInfo("NOAA APT", 137_000, 138_000, pub),
        BandInfo("2m HAM", 144_000, 148_000, ham),
        BandInfo("MARINE VHF", 156_000, 162_000, pub),
        BandInfo("NOAA WX", 162_400, 162_550, pub),
        BandInfo("1.25m HAM", 222_000, 225_000, ham),
        BandInfo("MIL UHF AIR/SATCOM", 225_000, 400_000, mil),
        BandInfo("70cm HAM", 420_000, 450_000, ham),
        BandInfo("PMR446", 446_000, 446_200, ism),
        BandInfo("433 ISM", 433_050, 434_790, ism),
        BandInfo("GSM850", 824_000, 894_000, cell),
        BandInfo("GMRS/FRS", 462_550, 467_725, ism),
        BandInfo("GSM900", 880_000, 960_000, cell),
        BandInfo("33cm HAM", 902_000, 928_000, ham),
        BandInfo("915 ISM", 902_000, 928_000, ism),
        BandInfo("PAGERS", 929_000, 932_000, pub),
        BandInfo("DCS/GSM1800", 1_710_000, 1_880_000, cell),
        BandInfo("GPS L1", 1_574_000, 1_577_000, nav),
        BandInfo("23cm HAM", 1_240_000, 1_300_000, ham),
    )
}
