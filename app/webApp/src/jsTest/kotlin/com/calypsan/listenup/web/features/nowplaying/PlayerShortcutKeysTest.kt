package com.calypsan.listenup.web.features.nowplaying

import com.calypsan.listenup.client.domain.model.LoadedBook
import com.calypsan.listenup.client.playback.PlayerPhase
import com.calypsan.listenup.client.presentation.player.PlayerChapterRow
import com.calypsan.listenup.client.presentation.player.PlayerContributor
import com.calypsan.listenup.client.presentation.player.PlayerOverlay
import com.calypsan.listenup.client.presentation.player.PlayerUiState
import com.calypsan.listenup.web.awaitFrame
import com.calypsan.listenup.web.awaitPopstate
import com.calypsan.listenup.web.mountAt
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.events.KeyboardEvent
import org.w3c.dom.events.KeyboardEventInit

/** Two titled chapters, the second (the last) current: Shift+→ there is the no-op the model owns. */
private fun wayOfKings(phase: PlayerPhase = PlayerPhase.Playing): PlayerUiState.Loaded =
    loadedPlayerState(
        title = "The Way of Kings",
        phase = phase,
        authors = listOf(PlayerContributor("c1", "Brandon Sanderson", 1)),
        chapters =
            listOf(
                PlayerChapterRow(0, 1, "Prelude", 0, 300_000, isCurrent = false),
                PlayerChapterRow(1, 2, "Bridge Four", 300_000, 300_000, isCurrent = false),
            ),
        currentChapterIndex = 1,
    )

/** A session over a fixed [state] that writes every gesture it is handed into [calls]. */
private fun recordingPlayback(
    state: PlayerUiState,
    calls: MutableList<String>,
): OpenPlayback =
    {
        PlaybackSession(
            state = MutableStateFlow(state),
            progress = MutableStateFlow(playerProgress()),
            loadedBook =
                flowOf(
                    if (state is PlayerUiState.Loaded) {
                        LoadedBook.Loaded(state.book.id, isPlaying = true, isFinished = false)
                    } else {
                        LoadedBook.NothingLoaded
                    },
                ),
            overlay = MutableStateFlow(PlayerOverlay.None),
            preparingBookId = MutableStateFlow(null),
            boostUnavailable = MutableStateFlow(false),
            events = emptyFlow(),
            onPrimary = { calls += "primary" },
            onPlayPause = { calls += "play/pause" },
            onPlayBook = { _, _ -> },
            onSeek = { calls += "seek $it" },
            onSkipBack = { calls += "skip back" },
            onSkipForward = { calls += "skip forward" },
            onPreviousChapter = { calls += "previous chapter" },
            onNextChapter = { calls += "next chapter" },
            onSeekToChapter = {},
            onSetSpeed = {},
            onResetSpeed = {},
            onStepSpeed = { up -> calls += if (up) "faster" else "slower" },
            onSetBoost = {},
            onResetBoost = {},
            onSetSleepTimer = {},
            onCancelSleepTimer = {},
            onExtendSleepTimer = {},
            onRetry = {},
            onDismissError = {},
            onCloseBook = {},
            onCardAction = {},
            onRate = {},
            close = {},
        )
    }

/** Presses [key] where focus is (or on the body), as a keyboard does: a bubbling keydown. */
private fun press(
    key: String,
    shift: Boolean = false,
    meta: Boolean = false,
) {
    val target = document.activeElement ?: document.body!!
    target.dispatchEvent(
        KeyboardEvent("keydown", KeyboardEventInit(key = key, shiftKey = shift, metaKey = meta, bubbles = true, cancelable = true)),
    )
}

/**
 * The player's keyboard through the shell's one key dispatcher (spec §5.8): every key of the table on a page, standing
 * down in a field, over the palette, and in the chapter editor.
 */
class PlayerShortcutKeysTest :
    FunSpec({
        test("the table's keys reach the player from any page") {
            val calls = mutableListOf<String>()
            val (_, router, composition) = mountAt("/library", openPlayback = recordingPlayback(wayOfKings(), calls))
            try {
                awaitFrame()
                (document.activeElement as? HTMLElement)?.blur()
                press(" ")
                press("ArrowLeft")
                press("ArrowRight")
                press("ArrowLeft", shift = true)
                // At the last chapter: the call happens, and the shared model makes it a no-op.
                press("ArrowRight", shift = true)
                press("<", shift = true)
                press(">", shift = true)

                calls shouldBe
                    listOf(
                        "play/pause",
                        "skip back",
                        "skip forward",
                        "previous chapter",
                        "next chapter",
                        "slower",
                        "faster",
                    )
            } finally {
                composition.dispose()
                router.dispose()
            }
        }

        test("in a chapterless book Shift+← does nothing, rather than jump to 0:00; ← still skips back") {
            val calls = mutableListOf<String>()
            val chapterless = loadedPlayerState(title = "Piranesi", authors = listOf(PlayerContributor("c3", "Susanna Clarke", 1)))
            val (_, router, composition) = mountAt("/library", openPlayback = recordingPlayback(chapterless, calls))
            try {
                awaitFrame()
                (document.activeElement as? HTMLElement)?.blur()
                press("ArrowLeft", shift = true)
                press("ArrowLeft")

                calls shouldBe listOf("skip back")
            } finally {
                composition.dispose()
                router.dispose()
            }
        }

        test("keys are ignored in an <input>: Space and T type there") {
            val calls = mutableListOf<String>()
            val (host, router, composition) = mountAt("/library", openPlayback = recordingPlayback(wayOfKings(), calls))
            try {
                awaitFrame()
                val field = document.createElement("input") as HTMLInputElement
                host.appendChild(field)
                field.focus()
                press(" ")
                press("t")
                press("ArrowLeft")
                awaitFrame()
                calls shouldBe emptyList()
                host.querySelector(".sleep-dlg").shouldBeNull()
                field.remove()

                // The bar's own range input keeps its arrows and Space too.
                (host.querySelector(".tport-scrub") as HTMLElement).focus()
                press(" ")
                press("ArrowLeft")
                calls shouldBe emptyList()
            } finally {
                composition.dispose()
                router.dispose()
            }
        }

        test("with the palette open, the arrows move nothing in the player; ⌘K still opens it with a book loaded") {
            val calls = mutableListOf<String>()
            val (host, router, composition) = mountAt("/library", openPlayback = recordingPlayback(wayOfKings(), calls))
            try {
                awaitFrame()
                (document.activeElement as? HTMLElement)?.blur()
                press("k", meta = true)
                awaitFrame()
                host.ownerDocument!!.querySelector("dialog[open]").shouldNotBeNull()
                press("ArrowLeft")
                press(" ")
                calls shouldBe emptyList()
            } finally {
                composition.dispose()
                router.dispose()
            }
        }

        test("P opens Now Playing, and P there closes it again") {
            val calls = mutableListOf<String>()
            val (_, router, composition) = mountAt("/library", openPlayback = recordingPlayback(wayOfKings(), calls))
            try {
                awaitFrame()
                (document.activeElement as? HTMLElement)?.blur()
                press("p")
                awaitFrame()
                window.location.pathname shouldBe "/playing"
                (document.activeElement as? HTMLElement)?.blur()
                press("p")
                awaitPopstate()
                awaitFrame()
                window.location.pathname shouldBe "/library"
            } finally {
                composition.dispose()
                router.dispose()
            }
        }

        test("T opens the sleep panel against the bar's Sleep, and Esc in it closes only the panel") {
            val calls = mutableListOf<String>()
            val (host, router, composition) = mountAt("/playing", openPlayback = recordingPlayback(wayOfKings(), calls))
            try {
                awaitFrame()
                (document.activeElement as? HTMLElement)?.blur()
                press("t")
                awaitFrame()
                val panel = host.ownerDocument!!.querySelector(".panel-pop.sleep-dlg").shouldNotBeNull()
                panel.contains(document.activeElement) shouldBe true
                press("Escape")
                awaitFrame()
                host.ownerDocument!!.querySelector(".sleep-dlg").shouldBeNull()
                window.location.pathname shouldBe "/playing"
                document.activeElement shouldBe host.querySelector(".tport-sleep")
            } finally {
                composition.dispose()
                router.dispose()
            }
        }

        test("? opens the keyboard shortcuts against the bar's Shortcuts button") {
            val calls = mutableListOf<String>()
            val (host, router, composition) = mountAt("/library", openPlayback = recordingPlayback(wayOfKings(), calls))
            try {
                awaitFrame()
                (document.activeElement as? HTMLElement)?.blur()
                press("?", shift = true)
                awaitFrame()
                val sheet = host.ownerDocument!!.querySelector(".panel-pop.keys-dlg").shouldNotBeNull()
                sheet.querySelector("h2")!!.textContent shouldBe "Keyboard shortcuts"
                sheet.querySelectorAll("dt").length shouldBe playerShortcuts().size
                press("Escape")
                awaitFrame()
                document.activeElement shouldBe host.querySelector(".tport-keys")
            } finally {
                composition.dispose()
                router.dispose()
            }
        }

        test("C on Now Playing, wide, focuses the page's chapter list") {
            val calls = mutableListOf<String>()
            val (host, router, composition) = mountAt("/playing", openPlayback = recordingPlayback(wayOfKings(), calls))
            try {
                awaitFrame()
                (document.activeElement as? HTMLElement)?.blur()
                press("c")
                awaitFrame()
                document.activeElement shouldBe host.querySelector(".np-list .np-row.on")
            } finally {
                composition.dispose()
                router.dispose()
            }
        }

        test("nothing loaded: the keys do nothing") {
            val calls = mutableListOf<String>()
            val (_, router, composition) =
                mountAt("/library", openPlayback = recordingPlayback(PlayerUiState.NothingLoaded, calls))
            try {
                awaitFrame()
                (document.activeElement as? HTMLElement)?.blur()
                press(" ")
                press("p")
                awaitFrame()
                calls shouldBe emptyList()
                window.location.pathname shouldBe "/library"
            } finally {
                composition.dispose()
                router.dispose()
            }
        }

        test("the shell listens for keys exactly once, however many features want them") {
            val restore =
                js(
                    "(function(){ var orig = window.addEventListener; window.__luKeydowns = 0;" +
                        " window.addEventListener = function(t, l, o) { if (t === 'keydown') window.__luKeydowns++;" +
                        " return orig.call(this, t, l, o); };" +
                        " return function() { window.addEventListener = orig; }; })()",
                )
            val (_, router, composition) =
                try {
                    mountAt("/playing", openPlayback = recordingPlayback(wayOfKings(), mutableListOf()))
                } finally {
                    awaitFrame()
                    restore()
                }
            try {
                js("window.__luKeydowns").unsafeCast<Int>() shouldBe 1
            } finally {
                composition.dispose()
                router.dispose()
            }
        }
    })
