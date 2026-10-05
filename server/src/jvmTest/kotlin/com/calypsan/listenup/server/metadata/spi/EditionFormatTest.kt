package com.calypsan.listenup.server.metadata.spi

import com.calypsan.listenup.api.dto.match.EditionFormat
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class EditionFormatTest :
    FunSpec({
        test("a catalogue's declared format is read") {
            editionFormatOf("unabridged", "Project Hail Mary") shouldBe EditionFormat.UNABRIDGED
            editionFormatOf("Abridged", "Project Hail Mary") shouldBe EditionFormat.ABRIDGED
        }

        test("a dramatisation marker in the title beats the declared format") {
            editionFormatOf("unabridged", "Project Hail Mary [Dramatized Adaptation]") shouldBe EditionFormat.DRAMATIZED
            editionFormatOf(null, "Dune", "A Full-Cast Production") shouldBe EditionFormat.DRAMATIZED
            editionFormatOf(null, "Dune (Dramatised)") shouldBe EditionFormat.DRAMATIZED
        }

        test("an abridged marker counts, and 'Unabridged' in a title is not one") {
            editionFormatOf(null, "Dune (Abridged)") shouldBe EditionFormat.ABRIDGED
            editionFormatOf(null, "Dune (Unabridged)") shouldBe EditionFormat.UNABRIDGED
        }

        test("nothing said means unknown") {
            editionFormatOf(null, "Dune") shouldBe null
            editionFormatOf("Audiobook", "Dune") shouldBe null
        }
    })
