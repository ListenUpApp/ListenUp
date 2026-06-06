package com.calypsan.listenup.server.scanner.pipeline

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class GenreSplitterTest :
    FunSpec({
        test("single genre passes through") {
            GenreSplitter.split("Fantasy") shouldBe listOf("Fantasy")
        }
        test("splits on semicolons, slashes, and commas") {
            GenreSplitter.split("Sci-Fi; Fantasy") shouldBe listOf("Sci-Fi", "Fantasy")
            GenreSplitter.split("Mystery / Thriller") shouldBe listOf("Mystery", "Thriller")
            GenreSplitter.split("Horror, Suspense") shouldBe listOf("Horror", "Suspense")
        }
        test("trims, drops blanks, and dedupes preserving order") {
            GenreSplitter.split("Fantasy;  ; Fantasy ; Epic") shouldBe listOf("Fantasy", "Epic")
        }
        test("blank input yields empty list") {
            GenreSplitter.split("   ") shouldBe emptyList()
        }
        test("hyphenated genre is not split (hyphen is not a separator)") {
            GenreSplitter.split("Sci-Fi") shouldBe listOf("Sci-Fi")
        }
    })
