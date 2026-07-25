package com.calypsan.listenup.server.sync

import com.calypsan.listenup.api.result.AppResult
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

private fun slug(raw: String): String = (TagSlug.normalize(raw) as AppResult.Success).data

/**
 * Proves slug normalization yields the SAME output on JVM (`java.text.Normalizer` NFKD) and linuxX64
 * (the [foldDiacritics] folding table). Each assertion is one literal both targets must hit, so the
 * native run is the proof the seam matches the historical JVM behaviour for realistic Latin display
 * names. (The full slug rules — errors, length — stay covered by the existing `TagSlugTest`.)
 */
class SlugDiacriticsTest :
    FunSpec({
        test("folds Latin diacritics identically on JVM and native") {
            slug("Café") shouldBe "cafe"
            slug("Köln") shouldBe "koln"
            slug("Señor") shouldBe "senor"
            slug("Naïve") shouldBe "naive"
            slug("Façade") shouldBe "facade"
            slug("Mötley Crüe") shouldBe "motley-crue"
            slug("Antonín Dvořák") shouldBe "antonin-dvorak"
            slug("Žižek") shouldBe "zizek"
        }

        test("foldDiacritics preserves case and plain text") {
            foldDiacritics("Crème Brûlée") shouldBe "Creme Brulee"
            foldDiacritics("plain ascii 123") shouldBe "plain ascii 123"
        }

        test("slug pipeline still handles ampersand and punctuation") {
            slug("Rock & Roll") shouldBe "rock-and-roll"
            slug("Sci-Fi / Fantasy!") shouldBe "sci-fi-fantasy"
        }
    })
