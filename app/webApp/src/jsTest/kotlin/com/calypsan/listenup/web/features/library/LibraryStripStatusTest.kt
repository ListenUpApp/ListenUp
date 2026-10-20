package com.calypsan.listenup.web.features.library

import com.calypsan.listenup.client.presentation.library.LibraryStatusStrip
import com.calypsan.listenup.web.MountRegistry
import com.calypsan.listenup.web.features.home.scanning
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.w3c.dom.HTMLElement

/** The Library's strip reads the shared decision, in the board's words, as a polite status region. */
class LibraryStripStatusTest :
    FunSpec({
        val mounts = MountRegistry()
        afterTest { mounts.disposeAll() }

        test("arriving reads the board's words with Room's count, in a polite status region") {
            val root = mounts.mount { LibraryStripStatus(LibraryStatusStrip.Arriving(booksSoFar = 1286)) }

            val strip = root.querySelector("[role=status]") as HTMLElement
            strip.textContent!! shouldContain "Building your library"
            strip.textContent!! shouldContain "1,286 books so far. Books appear here as they arrive; you can start listening now."
        }

        test("one book is one book") {
            val root = mounts.mount { LibraryStripStatus(LibraryStatusStrip.Arriving(booksSoFar = 1)) }

            root.querySelector(".lib-status-sub")!!.textContent shouldBe
                "1 book so far. Books appear here as they arrive; you can start listening now."
        }

        test("a scan names the server, the count and what is new") {
            val root = mounts.mount { LibraryStripStatus(LibraryStatusStrip.Scanning(scanning(books = 412, booksTotal = 1280))) }

            (root.querySelector("[role=status]") as HTMLElement).textContent!! shouldContain
                "Scanning your server412 of 1,280 · 3 new"
        }

        test("hidden renders nothing") {
            mounts.mount { LibraryStripStatus(LibraryStatusStrip.Hidden) }.querySelector("[role=status]") shouldBe null
        }
    })
