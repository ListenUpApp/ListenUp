package com.calypsan.listenup.web.features.home

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.calypsan.listenup.client.presentation.home.HomeSlot
import com.calypsan.listenup.client.presentation.home.HomeStatsUiState
import com.calypsan.listenup.client.presentation.home.HomeUiState
import com.calypsan.listenup.web.design.Button
import com.calypsan.listenup.web.design.ButtonKind
import com.calypsan.listenup.web.design.EmptyState
import com.calypsan.listenup.web.design.PageHeader
import com.calypsan.listenup.web.features.books.BookSelection
import com.calypsan.listenup.web.features.library.ArrivingWords
import com.calypsan.listenup.web.features.library.LibraryStatus
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.Text
import kotlin.time.Clock

/**
 * Home — the root route, and the first thing a reader sees after signing in.
 *
 * Renders two independent upstreams: [state] carries the slot, what else you are part way through, your
 * shelves and whether the library is still arriving; [stats] carries the last 7 days' listening. They load and fail
 * separately on purpose (see [HomeSession]), so a slow stats query never holds back the rest.
 *
 * Every book in progress is in Everything in progress, the loaded one first; there is no Resume hero (Simon, 2026-10-27).
 * [homeBlocks] decides the sections and their order from the slot and [isWide]; the page draws them in that order.
 *
 * Pure, like every other page here: no routing decisions, no session lifetime. Every gesture leaves through
 * [actions]; My shelves' See all leaves for the `/shelves` list (H24).
 */
@Composable
fun HomePage(
    state: HomeUiState,
    stats: HomeStatsUiState,
    actions: HomeActions,
    isWide: Boolean = false,
    nowMs: Long = remember { Clock.System.now().toEpochMilliseconds() },
    selection: BookSelection? = null,
) {
    Div(attrs = { classes("home") }) {
        when (state) {
            is HomeUiState.Loading -> {
                // Deliberately silent about WHY: before the first emission the page genuinely does
                // not know whether the library is empty, syncing or broken, and guessing out loud is
                // how a healthy first run ends up reading like a failure. The greeting's place is
                // held by a skeleton, under an H1 that still names the page.
                PageHeader(title = HomeWords.HOME, documentTitle = null, display = true, pending = true)
                SlotSkeleton()
                RailSkeleton()
            }

            is HomeUiState.Error -> {
                // The greeting stays (spec §3.3); the error carries no name.
                PageHeader(
                    title = homeGreeting(state.timeOfDay, firstName = null),
                    documentTitle = null,
                    display = true,
                )
                HomeErrorSlot(onRetry = actions.onRetry)
            }

            is HomeUiState.Ready -> {
                PageHeader(
                    title =
                        homeGreeting(
                            state.timeOfDay,
                            state.firstName,
                            isWelcome = state.slot is HomeSlot.NewListener,
                        ),
                    documentTitle = null,
                    display = true,
                )
                val blocks =
                    homeBlocks(
                        slot = state.slot,
                        isOffline = state.isOffline,
                        continueListeningFailed = state.continueListeningError != null,
                        showsStrip = state.showsArrivalStrip,
                        hasAlsoInProgress = state.everythingInProgress.isNotEmpty(),
                        isWide = isWide,
                    )
                blocks.forEach { block ->
                    HomeBlockContent(block, state, stats, actions, isWide, nowMs, selection)
                }
            }
        }
    }
}

/**
 * The library-still-arriving strip in Home's words (spec H5, open question 1): Library's strip, titled "Building your
 * library" with " · 412 of 1,280 books" while a scan knows both numbers, and Home's line about where your place is.
 */
@Composable
private fun HomeLibraryStatus(state: HomeUiState.Ready) {
    val scan = state.scanProgress
    LibraryStatus(
        scan = scan,
        isBuilding = true,
        words =
            ArrivingWords(
                title =
                    scan?.takeIf { it.booksTotal > 0 }?.let { HomeWords.arrivingTitle(it.books, it.booksTotal) }
                        ?: HomeWords.BUILDING_TITLE,
                body = HomeWords.ARRIVING_BODY,
            ),
    )
}

/** One block of [homeBlocks], drawn. */
@Composable
private fun HomeBlockContent(
    block: HomeBlock,
    state: HomeUiState.Ready,
    stats: HomeStatsUiState,
    actions: HomeActions,
    isWide: Boolean,
    nowMs: Long,
    selection: BookSelection?,
) {
    when (block) {
        HomeBlock.SlotBesideStats -> {
            Div(attrs = { classes("home-row") }) {
                SlotContent(state, actions, nowMs)
                LastSevenDays(stats, actions)
            }
        }

        HomeBlock.Finished, HomeBlock.NothingInProgress, HomeBlock.Welcome, HomeBlock.FirstSync -> {
            SlotContent(state, actions, nowMs)
        }

        HomeBlock.Offline -> {
            ServerNotice(onRetry = actions.onRetryConnection)
        }

        HomeBlock.FirstSyncStrip -> {
            HomeLibraryStatus(state)
        }

        HomeBlock.ContinueListeningFailed -> {
            ContinueListeningFailedLine(onRetry = actions.onRetry)
        }

        HomeBlock.AlsoInProgress -> {
            AlsoInProgress(state.everythingInProgress, isWide, actions, selection)
        }

        HomeBlock.Stats -> {
            LastSevenDays(stats, actions)
        }

        HomeBlock.Shelves -> {
            MyShelves(state.myShelves, actions, isLoading = state.showsShelvesLoading)
        }

        HomeBlock.StatsBesideShelves -> {
            Div(attrs = { classes("home-row") }) {
                LastSevenDays(stats, actions)
                MyShelves(state.myShelves, actions, isLoading = state.showsShelvesLoading)
            }
        }
    }
}

/** Whichever slot applies, drawn: every kind takes the same position (spec §2.1). */
@Composable
private fun SlotContent(
    state: HomeUiState.Ready,
    actions: HomeActions,
    nowMs: Long,
) {
    when (val slot = state.slot) {
        is HomeSlot.JustFinished -> {
            HomeFinishedCard(slot.card, nowMs, actions)
        }

        is HomeSlot.NothingInProgress -> {
            NothingInProgressSlot(slot, actions)
        }

        is HomeSlot.NewListener -> {
            WelcomeSlot(slot, actions)
        }

        HomeSlot.FirstSync -> {
            SlotSkeleton()
        }

        HomeSlot.Absent -> {
            Unit
        }
    }
}
