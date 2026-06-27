package com.calypsan.listenup.server.sync

import com.calypsan.listenup.api.result.AppResult
import io.kotest.matchers.shouldBe
import kotlin.test.Test

/**
 * Proves slug normalization yields the SAME output on JVM (`java.text.Normalizer` NFKD) and linuxX64
 * (the [foldDiacritics] folding table). Each assertion is one literal both targets must hit, so the
 * native run is the proof the seam matches the historical JVM behaviour for realistic Latin display
 * names. (The full slug rules — errors, length — stay covered by the existing `TagSlugTest`.)
 */
class SlugDiacriticsTest {
    private fun slug(raw: String): String = (TagSlug.normalize(raw) as AppResult.Success).data

    @Test
    fun foldsLatinDiacriticsIdenticallyOnJvmAndNative() {
        slug("Café") shouldBe "cafe"
        slug("Köln") shouldBe "koln"
        slug("Señor") shouldBe "senor"
        slug("Naïve") shouldBe "naive"
        slug("Façade") shouldBe "facade"
        slug("Mötley Crüe") shouldBe "motley-crue"
        slug("Antonín Dvořák") shouldBe "antonin-dvorak"
        slug("Žižek") shouldBe "zizek"
    }

    @Test
    fun foldDiacriticsPreservesCaseAndPlainText() {
        foldDiacritics("Crème Brûlée") shouldBe "Creme Brulee"
        foldDiacritics("plain ascii 123") shouldBe "plain ascii 123"
    }

    @Test
    fun slugPipelineStillHandlesAmpersandAndPunctuation() {
        slug("Rock & Roll") shouldBe "rock-and-roll"
        slug("Sci-Fi / Fantasy!") shouldBe "sci-fi-fantasy"
    }
}
