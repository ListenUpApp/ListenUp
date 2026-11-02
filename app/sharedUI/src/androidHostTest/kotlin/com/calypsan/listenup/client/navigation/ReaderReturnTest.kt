package com.calypsan.listenup.client.navigation

import androidx.navigation3.runtime.NavKey
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * The PDF opened from Now Playing shows above it and returns to it, as iOS presents the reader over Now Playing and
 * its Done returns there: a plain Back out of that reader re-expands Now Playing; any other way in or past it does not.
 */
class ReaderReturnTest :
    FunSpec({
        val fromNowPlaying = DocumentViewer(localPath = "/cache/doc.pdf", title = "Book", opensFromNowPlaying = true)
        val fromBookDetail = DocumentViewer(localPath = "/cache/doc.pdf", title = "Book")

        test("backing out of a reader opened from Now Playing returns to Now Playing") {
            val previous = listOf<NavKey>(Shell, BookDetail("b1"), fromNowPlaying)
            reexpandsNowPlaying(previous = previous, current = listOf(Shell, BookDetail("b1"))) shouldBe true
        }

        test("backing out of a reader opened from Book Detail stays on Book Detail") {
            val previous = listOf<NavKey>(Shell, BookDetail("b1"), fromBookDetail)
            reexpandsNowPlaying(previous = previous, current = listOf(Shell, BookDetail("b1"))) shouldBe false
        }

        test("a screen pushed over the reader does not reopen Now Playing") {
            val previous = listOf<NavKey>(Shell, fromNowPlaying)
            reexpandsNowPlaying(previous = previous, current = listOf(Shell, fromNowPlaying, Login)) shouldBe false
        }

        test("leaving any other screen does not reopen Now Playing") {
            reexpandsNowPlaying(previous = listOf(Shell, BookDetail("b1")), current = listOf(Shell)) shouldBe false
            reexpandsNowPlaying(previous = emptyList(), current = listOf(Shell)) shouldBe false
        }

        test("an outside navigation that clears the stack does not reopen Now Playing") {
            val previous = listOf<NavKey>(Shell, BookDetail("b1"), fromNowPlaying)
            reexpandsNowPlaying(previous = previous, current = listOf(Shell, BookDetail("b9"))) shouldBe false
        }
    })
