package com.example.rtlsdr

/**
 * Stage 6 of the KEYUP chain: AMBE+2 half-rate vocoder, turning the 72-bit subframes [DmrVerify]
 * extracts per voice burst into 8 kHz PCM. Implemented entirely in Kotlin ([AmbeDecoder]) -- no native
 * library, no JNI, no external dependency, so [available] is always true.
 */
object DmrVocoder {
    val available: Boolean = true

    private val decoder = AmbeDecoder()

    /** Forces the lookup tables to be parsed now (they are lazy), so the first real voice frame doesn't stall the radio thread. */
    fun warmUp() { try { AmbeDecoder().decode(IntArray(72)) } catch (_: Exception) {} }

    /** Drop the previous-frame history, e.g. when a new call starts or the frequency changes. */
    @Synchronized fun reset() = decoder.reset()

    /** [subframe72] = one AMBE+2 subframe's raw bits (0/1 per element, length 72). Returns 160 samples of
        8 kHz signed 16-bit PCM (20 ms), or null for a malformed input. */
    @Synchronized fun decode(subframe72: IntArray): ShortArray? =
        try { decoder.decode(subframe72) } catch (_: Exception) { null }
}
