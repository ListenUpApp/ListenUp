package com.calypsan.listenup.server.auth

import io.kotest.matchers.shouldBe
import kotlin.test.Test

private const val GRAVE = "̀"
private const val ACUTE = "́"
private const val CIRCUMFLEX = "̂"
private const val TILDE = "̃"
private const val MACRON = "̄"
private const val BREVE = "̆"
private const val DOT_ABOVE = "̇"
private const val DIAERESIS = "̈"
private const val RING_ABOVE = "̊"
private const val DOUBLE_ACUTE = "̋"
private const val CARON = "̌"
private const val CEDILLA = "̧"
private const val OGONEK = "̨"

/**
 * Proves [normalizeNfc] yields the SAME output on JVM (`java.text.Normalizer` NFC) and linuxX64 (the
 * canonical-composition table). Each case feeds a decomposed `base + combining mark` and asserts the
 * precomposed letter, so the native run is the proof the seam matches the historical JVM behaviour
 * for the realistic lowercase Latin email domain. Because JVM is true Unicode NFC, any wrong table
 * entry (or a composition exclusion) fails the JVM run here too.
 */
class NfcNormalizeParityTest {
    @Test
    fun composesLowercaseLatinDiacriticsIdenticallyOnJvmAndNative() {
        // (base, combining mark) -> NFC precomposed letter. Input is `base + mark` (decomposed, NFD).
        val cases =
            listOf(
                Triple("a", GRAVE, "à"),
                Triple("a", ACUTE, "á"),
                Triple("a", CIRCUMFLEX, "â"),
                Triple("a", TILDE, "ã"),
                Triple("a", DIAERESIS, "ä"),
                Triple("a", RING_ABOVE, "å"),
                Triple("a", MACRON, "ā"),
                Triple("a", BREVE, "ă"),
                Triple("a", OGONEK, "ą"),
                Triple("c", CEDILLA, "ç"),
                Triple("c", ACUTE, "ć"),
                Triple("c", CIRCUMFLEX, "ĉ"),
                Triple("c", DOT_ABOVE, "ċ"),
                Triple("c", CARON, "č"),
                Triple("d", CARON, "ď"),
                Triple("e", GRAVE, "è"),
                Triple("e", ACUTE, "é"),
                Triple("e", CIRCUMFLEX, "ê"),
                Triple("e", DIAERESIS, "ë"),
                Triple("e", MACRON, "ē"),
                Triple("e", BREVE, "ĕ"),
                Triple("e", DOT_ABOVE, "ė"),
                Triple("e", OGONEK, "ę"),
                Triple("e", CARON, "ě"),
                Triple("g", CIRCUMFLEX, "ĝ"),
                Triple("g", BREVE, "ğ"),
                Triple("g", DOT_ABOVE, "ġ"),
                Triple("g", CEDILLA, "ģ"),
                Triple("h", CIRCUMFLEX, "ĥ"),
                Triple("i", GRAVE, "ì"),
                Triple("i", ACUTE, "í"),
                Triple("i", CIRCUMFLEX, "î"),
                Triple("i", DIAERESIS, "ï"),
                Triple("i", TILDE, "ĩ"),
                Triple("i", MACRON, "ī"),
                Triple("i", BREVE, "ĭ"),
                Triple("i", OGONEK, "į"),
                Triple("j", CIRCUMFLEX, "ĵ"),
                Triple("k", CEDILLA, "ķ"),
                Triple("l", ACUTE, "ĺ"),
                Triple("l", CEDILLA, "ļ"),
                Triple("l", CARON, "ľ"),
                Triple("n", TILDE, "ñ"),
                Triple("n", ACUTE, "ń"),
                Triple("n", CEDILLA, "ņ"),
                Triple("n", CARON, "ň"),
                Triple("o", GRAVE, "ò"),
                Triple("o", ACUTE, "ó"),
                Triple("o", CIRCUMFLEX, "ô"),
                Triple("o", TILDE, "õ"),
                Triple("o", DIAERESIS, "ö"),
                Triple("o", MACRON, "ō"),
                Triple("o", BREVE, "ŏ"),
                Triple("o", DOUBLE_ACUTE, "ő"),
                Triple("r", ACUTE, "ŕ"),
                Triple("r", CEDILLA, "ŗ"),
                Triple("r", CARON, "ř"),
                Triple("s", ACUTE, "ś"),
                Triple("s", CIRCUMFLEX, "ŝ"),
                Triple("s", CEDILLA, "ş"),
                Triple("s", CARON, "š"),
                Triple("t", CEDILLA, "ţ"),
                Triple("t", CARON, "ť"),
                Triple("u", GRAVE, "ù"),
                Triple("u", ACUTE, "ú"),
                Triple("u", CIRCUMFLEX, "û"),
                Triple("u", DIAERESIS, "ü"),
                Triple("u", TILDE, "ũ"),
                Triple("u", MACRON, "ū"),
                Triple("u", BREVE, "ŭ"),
                Triple("u", RING_ABOVE, "ů"),
                Triple("u", DOUBLE_ACUTE, "ű"),
                Triple("u", OGONEK, "ų"),
                Triple("w", CIRCUMFLEX, "ŵ"),
                Triple("y", ACUTE, "ý"),
                Triple("y", DIAERESIS, "ÿ"),
                Triple("y", CIRCUMFLEX, "ŷ"),
                Triple("z", ACUTE, "ź"),
                Triple("z", DOT_ABOVE, "ż"),
                Triple("z", CARON, "ž"),
            )
        cases.forEach { (base, mark, composed) -> normalizeNfc(base + mark) shouldBe composed }
    }

    @Test
    fun passesThroughAsciiAndAlreadyComposedText() {
        normalizeNfc("user.name@example.com") shouldBe "user.name@example.com"
        normalizeNfc("café") shouldBe "café"
        normalizeNfc("señor@correo.es") shouldBe "señor@correo.es"
        normalizeNfc("") shouldBe ""
    }

    @Test
    fun composesDecomposedSequencesWithinFullEmailStrings() {
        normalizeNfc("jose" + ACUTE + "@example.com") shouldBe "josé@example.com"
        normalizeNfc("mu" + DIAERESIS + "ller@firma.de") shouldBe "müller@firma.de"
        normalizeNfc("dvor" + CARON + "ak@hudba.cz") shouldBe "dvořak@hudba.cz"
    }
}
