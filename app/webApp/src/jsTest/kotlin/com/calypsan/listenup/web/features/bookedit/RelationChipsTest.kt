package com.calypsan.listenup.web.features.bookedit

import com.calypsan.listenup.client.domain.model.EditableGenre
import com.calypsan.listenup.client.domain.model.EditableTag
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * The label rules, pinned without a form around them.
 *
 * A slug reaching the page unformatted is the failure that matters here: it renders perfectly,
 * looks deliberate, and prints "found-family" on somebody's book.
 */
class RelationChipsTest :
    FunSpec({

        test("a tag's slug becomes words a reader recognises") {
            EditableTag(id = "t1", slug = "found-family").toChip().label shouldBe "Found Family"
        }

        test("an underscored slug is treated the same as a hyphenated one") {
            slugLabel("slow_burn") shouldBe "Slow Burn"
        }

        test("a single-word slug is capitalised, not left lowercase") {
            slugLabel("cosy") shouldBe "Cosy"
        }

        test("repeated separators do not become empty words") {
            slugLabel("epic--fantasy") shouldBe "Epic Fantasy"
        }

        test("a genre keeps its name and its id, because removal keys on the id") {
            val chip = EditableGenre(id = "g1", name = "Science Fiction", path = "/sf").toChip()

            chip.label shouldBe "Science Fiction"
            chip.id shouldBe "g1"
        }
    })
