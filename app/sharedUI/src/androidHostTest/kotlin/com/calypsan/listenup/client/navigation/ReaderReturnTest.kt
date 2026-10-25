package com.calypsan.listenup.client.navigation

import androidx.navigation3.runtime.NavKey
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * The PDF opened from Now Playing shows above it and returns to it, as iOS presents the reader over Now Playing and
 * its Done returns there: leaving that reader re-expands Now Playing; any other way in or past it does not.
 */
class ReaderReturnTest :
    FunSpec({
        val fromNowPlaying = DocumentViewer(localPath = "/cache/doc.pdf", opensFromNowPlaying = true)
        val fromBookDetail = DocumentViewer(localPath = "/cache/doc.pdf")

        test("backing out of a reader opened from Now Playing returns to Now Playing") {
            val stack = listOf<NavKey>(Shell, BookDetail("b1"))
            reexpandsNowPlaying(leftTop = fromNowPlaying, stack = stack) shouldBe true
        }

        test("backing out of a reader opened from Book Detail stays on Book Detail") {
            val stack = listOf<NavKey>(Shell, BookDetail("b1"))
            reexpandsNowPlaying(leftTop = fromBookDetail, stack = stack) shouldBe false
        }

        test("a screen pushed over the reader does not reopen Now Playing") {
            val stack = listOf<NavKey>(Shell, fromNowPlaying, Login)
            reexpandsNowPlaying(leftTop = fromNowPlaying, stack = stack) shouldBe false
        }

        test("leaving any other screen does not reopen Now Playing") {
            reexpandsNowPlaying(leftTop = BookDetail("b1"), stack = listOf(Shell)) shouldBe false
            reexpandsNowPlaying(leftTop = null, stack = listOf(Shell)) shouldBe false
        }
    })
