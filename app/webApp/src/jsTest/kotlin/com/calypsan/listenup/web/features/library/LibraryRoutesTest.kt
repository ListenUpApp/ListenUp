package com.calypsan.listenup.web.features.library

import com.calypsan.listenup.client.presentation.library.LibraryUiEvent
import com.calypsan.listenup.web.MountRegistry
import com.calypsan.listenup.web.awaitFrame
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe

/** `/library?sort=added-desc` shows the Books newest first for this visit; a plain `/library` asks nothing. */
class LibraryRoutesTest :
    FunSpec({
        val mounts = MountRegistry()
        afterTest { mounts.disposeAll() }

        test("sort=added-desc asks for newest first, exactly once") {
            val sent = mutableListOf<LibraryUiEvent>()
            mounts.mount { LibrarySortLanding(sortQuery = "added-desc", onEvent = { sent += it }) }
            awaitFrame()
            awaitFrame()

            sent shouldBe listOf(LibraryUiEvent.BooksShownNewestFirst)
        }

        test("no sort query asks for nothing, so the reader's own sort stands") {
            val sent = mutableListOf<LibraryUiEvent>()
            mounts.mount { LibrarySortLanding(sortQuery = null, onEvent = { sent += it }) }
            awaitFrame()

            sent.shouldBeEmpty()
        }

        test("an unknown sort is ignored") {
            val sent = mutableListOf<LibraryUiEvent>()
            mounts.mount { LibrarySortLanding(sortQuery = "title", onEvent = { sent += it }) }
            awaitFrame()

            sent.shouldBeEmpty()
        }
    })
