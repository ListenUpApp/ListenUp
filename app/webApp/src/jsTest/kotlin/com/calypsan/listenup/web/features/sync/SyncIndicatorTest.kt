package com.calypsan.listenup.web.features.sync

import com.calypsan.listenup.client.presentation.sync.SyncIndicatorUiState
import com.calypsan.listenup.web.ViewportFrames
import com.calypsan.listenup.web.awaitFrame
import com.calypsan.listenup.web.design.WebIcon
import com.calypsan.listenup.web.shell.NavEntry
import com.calypsan.listenup.web.shell.NavSection
import com.calypsan.listenup.web.shell.Shell
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.doubles.shouldBeLessThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

private const val NOW = 100_000_000L
private const val MINUTE = 60_000L

/** One sync indicator, two drawings: the phone header's button and the desktop sidebar's line (spec L29). */
class SyncIndicatorTest :
    FunSpec({
        val frames = ViewportFrames()
        afterTest { frames.disposeAll() }

        test("the words say how long ago, or that a sync is running, or that none has finished yet") {
            syncedWords(SyncIndicatorUiState(lastSyncedAtMs = NOW - 20_000), NOW) shouldBe "Synced just now"
            syncedWords(SyncIndicatorUiState(lastSyncedAtMs = NOW - 2 * MINUTE), NOW) shouldBe "Synced 2 min ago"
            syncedWords(SyncIndicatorUiState(lastSyncedAtMs = NOW - 185 * MINUTE), NOW) shouldBe "Synced 3 h ago"
            syncedWords(SyncIndicatorUiState(lastSyncedAtMs = NOW - 3 * 1_440 * MINUTE), NOW) shouldBe "Synced 3 d ago"
            syncedWords(SyncIndicatorUiState(isSyncing = true, lastSyncedAtMs = NOW), NOW) shouldBe "Syncing…"
            syncedWords(SyncIndicatorUiState(), NOW) shouldBe "Not synced yet"
            // A failed "sync now" is said until the next one, never covered by the last good sync.
            syncedWords(SyncIndicatorUiState(lastSyncedAtMs = NOW - 20_000, refreshFailed = true), NOW) shouldBe "Couldn't sync"
        }

        test("the sync button says 'Synced 2 min ago' and Refresh calls the ViewModel") {
            var refreshes = 0
            val session = fixedSyncIndicator(SyncIndicatorUiState(lastSyncedAtMs = NOW - 2 * MINUTE)) { refreshes++ }()
            val frame =
                frames.mount(width = 390) {
                    SyncIndicatorButton(state = session.state.value, now = NOW, onRefresh = session.onRefresh)
                }
            awaitFrame()

            val button = frame.find(".sync-ind-b")
            button.getAttribute("aria-label") shouldBe "Synced 2 min ago. Refresh"
            button.click()
            refreshes shouldBe 1
        }

        test("at ≥ 1024px the sidebar carries 'Synced 2 min ago · Refresh now'") {
            var refreshes = 0
            val frame =
                frames.mount(width = 1440) {
                    Shell(
                        sections = listOf(NavSection(listOf(NavEntry("home", "Home", WebIcon.Home, href = "/")))),
                        active = "home",
                        sidebarStatus = {
                            SyncStatusLine(
                                state = SyncIndicatorUiState(lastSyncedAtMs = NOW - 2 * MINUTE),
                                now = NOW,
                                onRefresh = { refreshes++ },
                            )
                        },
                    ) {}
                }
            awaitFrame()

            val line = frame.find(".sidebar .sb-status")
            frame.isShown(line) shouldBe true
            line.textContent.orEmpty() shouldContain "Synced 2 min ago"
            line.textContent.orEmpty() shouldContain "Refresh now"
            // "Refresh now" starts where "Synced 2 min ago" starts: one column, not a button nudged right by its padding.
            val words = frame.rect(frame.find(".sync-ind-t")).left
            val button = frame.find(".sync-ind-refresh")
            // Where its words start: the box's left plus its own padding.
            val refresh =
                frame.rect(button).left + frame.css(button, "padding-left").removeSuffix("px").toDouble() +
                    frame.css(button, "border-left-width").removeSuffix("px").toDouble()
            (refresh - words) shouldBeLessThan 1.5
            (words - refresh) shouldBeLessThan 1.5
            frame.find(".sync-ind-refresh").click()
            refreshes shouldBe 1
        }
    })
