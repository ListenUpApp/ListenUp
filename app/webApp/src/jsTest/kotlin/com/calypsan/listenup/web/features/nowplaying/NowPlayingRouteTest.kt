package com.calypsan.listenup.web.features.nowplaying

import com.calypsan.listenup.client.domain.model.LoadedBook
import com.calypsan.listenup.client.presentation.player.PlayerUiState
import com.calypsan.listenup.web.awaitFrame
import com.calypsan.listenup.web.mountAt
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.browser.window
import kotlinx.coroutines.flow.MutableStateFlow

/** S4: `/playing` is a page; with nothing loaded it goes Home, but never while the loaded book is still restoring. */
class NowPlayingRouteTest :
    FunSpec({
        test("a deep link with a book loaded opens the page, headed Now playing") {
            val (host, router, composition) = mountAt("/playing", openPlayback = fixedPlayback(state = loadedPlayerState()))
            try {
                awaitFrame()
                host.querySelector(".np-page h1")?.textContent shouldBe "Now playing"
                window.location.pathname shouldBe "/playing"
            } finally {
                composition.dispose()
                router.dispose()
            }
        }

        test("with nothing loaded, /playing goes Home, replacing the entry") {
            val (_, router, composition) =
                mountAt("/playing", openPlayback = fixedPlayback(state = PlayerUiState.NothingLoaded))
            try {
                awaitFrame()
                awaitFrame()
                window.location.pathname shouldBe "/"
            } finally {
                composition.dispose()
                router.dispose()
            }
        }

        test("while the loaded book is still restoring, it waits rather than bouncing Home") {
            val loaded = MutableStateFlow<LoadedBook>(LoadedBook.Restoring)
            val (host, router, composition) =
                mountAt(
                    "/playing",
                    openPlayback = fixedPlayback(state = PlayerUiState.NothingLoaded, loadedBook = loaded),
                )
            try {
                awaitFrame()
                awaitFrame()
                window.location.pathname shouldBe "/playing"
                host.querySelector(".np-page") shouldBe null
            } finally {
                composition.dispose()
                router.dispose()
            }
        }

        test("on /playing the shell goes immersive, so a phone hides the tabs, the header and the bar") {
            val (host, router, composition) = mountAt("/playing", openPlayback = fixedPlayback(state = loadedPlayerState()))
            try {
                awaitFrame()
                host.querySelector(".shell-immersive").shouldNotBeNull()
            } finally {
                composition.dispose()
                router.dispose()
            }
        }
    })
