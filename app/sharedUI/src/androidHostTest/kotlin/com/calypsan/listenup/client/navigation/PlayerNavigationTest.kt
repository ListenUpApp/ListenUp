package com.calypsan.listenup.client.navigation

import androidx.navigation3.runtime.NavKey
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * The player's navigation events land on the same screens wherever a player raises them — the mini-player and Now
 * Playing, or the PDF reader's strip and listening pane.
 */
class PlayerNavigationTest :
    FunSpec({
        val reader = DocumentViewer(localPath = "/cache/doc.pdf", title = "The Salt Roads")

        test("Sign in from the reader opens sign-in above it") {
            val stack = mutableListOf<NavKey>(Shell, BookDetail("b1"), reader)
            stack.playerNavigation().onOpenSignIn()
            stack shouldBe listOf(Shell, BookDetail("b1"), reader, Login)
        }

        test("Sign in is not pushed twice") {
            val stack = mutableListOf<NavKey>(Shell, reader, Login)
            stack.playerNavigation().onOpenSignIn()
            stack shouldBe listOf(Shell, reader, Login)
        }

        test("Go to book opens the book's page") {
            val stack = mutableListOf<NavKey>(Shell, reader)
            stack.playerNavigation().onNavigateToBook("b2")
            stack shouldBe listOf(Shell, reader, BookDetail("b2"))
        }

        test("the inbox opens the admin inbox") {
            val stack = mutableListOf<NavKey>(Shell, reader)
            stack.playerNavigation().onOpenInbox()
            stack shouldBe listOf(Shell, reader, AdminInbox)
        }

        test("a document opens the reader, remembering whether Now Playing opened it") {
            val stack = mutableListOf<NavKey>(Shell)
            stack.playerNavigation().onNavigateToDocument("/cache/doc.pdf", "The Salt Roads", true)
            stack shouldBe
                listOf(Shell, DocumentViewer("/cache/doc.pdf", title = "The Salt Roads", opensFromNowPlaying = true))
        }

        test("the reader is titled with the book's name, as iOS titles it, not the file's id") {
            val stack = mutableListOf<NavKey>(Shell)
            stack.playerNavigation().onNavigateToDocument("/cache/752953c7.pdf", "The Salt Roads", false)
            (stack.last() as DocumentViewer).title shouldBe "The Salt Roads"
        }
    })
