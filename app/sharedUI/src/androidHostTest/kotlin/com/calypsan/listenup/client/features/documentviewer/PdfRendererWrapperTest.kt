package com.calypsan.listenup.client.features.documentviewer

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * Unit tests for pure functions in the document-viewer package.
 *
 * [PdfRendererWrapper] itself requires Android's [android.graphics.pdf.PdfRenderer] and
 * a real device/emulator — it cannot be exercised in a JVM host test.  The pure helper
 * [basenameOf] is tested here as the unit-testable surface.
 */
class PdfRendererWrapperTest :
    FunSpec({
        test("basenameOf returns the filename from an absolute path") {
            basenameOf("/data/user/0/com.example/cache/book-1/supplement.pdf") shouldBe "supplement.pdf"
        }

        test("basenameOf returns the filename from a nested path") {
            basenameOf("/cache/docs/chapter-notes.pdf") shouldBe "chapter-notes.pdf"
        }

        test("basenameOf returns the filename when path has no directory component") {
            basenameOf("document.pdf") shouldBe "document.pdf"
        }
    })
