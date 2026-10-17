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
 * The loaded book is never drawn here — the player bar has it (spec §2.1). With nothing loaded, Resume leads.
 * [homeBlocks] decides the sections and their order from the slot and [isWide]; the page draws them in that order.
 *
 * Pure, like every other page here: no routing decisions, no session lifetime. Every gesture leaves through
 * [actions]. There is no "See all" anywhere: the My shelves list arrives with Home PR 4 (open question 1).
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
                PageHeader(title = HOME, documentTitle = null, display = true, pending = true)
            }

            is HomeUiState.Error -> {
                PageHeader(title = HOME, documentTitle = null)
                EmptyState(title = "Home is unavailable", body = state.error.message) {
                    Button(kind = ButtonKind.Primary, onClick = { actions.onRetry() }) { Text("Try again") }
                }
            }

            is HomeUiState.Ready -> {
                PageHeader(title = homeGreeting(state.timeOfDay, state.firstName), documentTitle = null, display = true)
                LibraryStatus(scan = state.scanProgress, isBuilding = state.isBuildingInitialLibrary)
                homeBlocks(state.slot, state.alsoInProgress.isNotEmpty(), isWide).forEach { block ->
                    HomeBlockContent(block, state, stats, actions, isWide, nowMs, selection)
                }
            }
        }
    }
}

private const val HOME = "Home"

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
    val isOnlyBook = state.slot is HomeSlot.Resume && state.alsoInProgress.isEmpty()
    val resume = state.slot as? HomeSlot.Resume
    when (block) {
        HomeBlock.Resume -> {
            resume?.let { ResumeHero(it, isOnlyBook, nowMs, actions.onOpenBook, actions.onPlay) }
        }

        HomeBlock.ResumeBesideStats -> {
            Div(attrs = { classes("home-row") }) {
                resume?.let { ResumeHero(it, isOnlyBook, nowMs, actions.onOpenBook, actions.onPlay) }
                LastSevenDays(stats, actions)
            }
        }

        HomeBlock.NothingToResume -> {
            // Interim for NothingInProgress and NewListener until Home PR 4 draws them (open question 5).
            EmptyState(title = "Nothing on the go", body = "Start a book and it will wait for you here.") {
                Button(kind = ButtonKind.Primary, onClick = { actions.onOpenLibrary() }) { Text("Browse the Library") }
            }
        }

        HomeBlock.AlsoInProgress -> {
            AlsoInProgress(state.alsoInProgress, isWide, actions, selection)
        }

        HomeBlock.Stats -> {
            LastSevenDays(stats, actions)
        }

        HomeBlock.Shelves -> {
            MyShelves(state.myShelves, actions)
        }

        HomeBlock.StatsBesideShelves -> {
            Div(attrs = { classes("home-row") }) {
                LastSevenDays(stats, actions)
                MyShelves(state.myShelves, actions)
            }
        }
    }
}
