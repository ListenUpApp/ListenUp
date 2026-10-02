package com.calypsan.listenup.konsist

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe

/** Planted inputs for [WebBookCoversCarryTheLockRule]'s matcher, so it is known to fire. */
class WebBookCoversCarryTheLockRuleSelfTest :
    FunSpec({
        test("a Cover without overlay is caught, on one line or many") {
            val source =
                """
                |Cover(title = book.title, imageUrl = url)
                |Cover(
                |    title = book.title,
                |    imageUrl = coverUrl(book.id, book.coverHash, width = W),
                |    decorative = true,
                |)
                |Cover(
                |    title = book.title,
                |    overlay = { RestrictedMarker(book.id) },
                |)
                """.trimMargin()

            bareCoverLines(source) shouldBe listOf(1, 2)
        }

        test("prose, the declaration and look-alike names are not calls") {
            val source =
                """
                |// Cover(title) draws the art.
                | * A Cover( in KDoc.
                |fun Cover(
                |    title: String,
                |)
                |CardCover(book, flyBack, selecting)
                |design.Cover(title = t, overlay = { })
                """.trimMargin()

            bareCoverLines(source).shouldBeEmpty()
        }
    })
