package com.calypsan.listenup.web.features.chaptereditor

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.calypsan.listenup.client.presentation.chaptereditor.ChapterEditorEvent
import com.calypsan.listenup.client.presentation.chaptereditor.ChapterEditorUiState
import com.calypsan.listenup.web.features.nowplaying.PlaybackSession
import com.calypsan.listenup.web.nav.Router
import com.calypsan.listenup.web.nav.Routes
import com.calypsan.listenup.web.nav.rememberSession
import com.calypsan.listenup.web.nav.rememberUnsavedWorkGuard

/**
 * Opens a Chapter Editor session for [bookId], collects it, and guards the way out.
 *
 * ⛔ **Leaving is not just navigation here.** This page holds the only copy of the reader's unsaved
 * work, so both ways out — the Back control and the browser's own Back — pass through a
 * confirmation while the draft is dirty. `onbeforeunload` covers the third: a closed tab.
 *
 * `Saved` navigates rather than replacing: the reader chose to come here, and the book's page is
 * where they came from.
 */
@Composable
internal fun ChapterEditorRoute(
    router: Router,
    openChapterEditor: OpenChapterEditor,
    bookId: String,
    playback: PlaybackSession,
) {
    val session = rememberSession(bookId) { openChapterEditor(bookId) }

    val state = session.state.collectAsState().value
    val transport = playback.state.collectAsState().value
    val nowPlaying = playback.nowPlaying.collectAsState().value
    // Only this book's transport counts. Anything else and the playhead is not about what is on
    // screen, so it is absent rather than misleading.
    val playheadMs = if (nowPlaying?.bookId == bookId) transport?.positionMs else null

    var problem by remember(bookId) { mutableStateOf<String?>(null) }
    val isDirty = (state as? ChapterEditorUiState.Editing)?.isDirty == true
    val guard = rememberUnsavedWorkGuard(dirty = isDirty, onDiscard = session.onResetToSource)
    val book = Routes.Book.detail(bookId)

    LaunchedEffect(session) {
        session.events.collect { event ->
            when (event) {
                // Saved is the page's own departure: the stale draft flag must not ask about it.
                ChapterEditorEvent.Saved -> {
                    router.navigate(book, force = true)
                }

                // SaveFailed already reaches the reader through the global error bus; repeating it
                // here would show the same failure twice.
                is ChapterEditorEvent.SaveFailed -> {}

                is ChapterEditorEvent.Invalid -> {
                    val chapters = (session.state.value as? ChapterEditorUiState.Editing)?.chapters.orEmpty()
                    problem = chapterProblemText(event.problems, chapters)
                }
            }
        }
    }

    ChapterEditorPage(
        state = state,
        playheadMs = playheadMs,
        onSelect = session.onSelect,
        onNudge = session.onNudge,
        onSnapToPlayhead = session.onSnapToPlayhead,
        onRetitle = session.onRetitle,
        onRemove = session.onRemove,
        onAddAt = session.onAddAt,
        onRetime = session.onRetime,
        onInsertBelow = session.onInsertBelow,
        onPlayFrom = session.onPlayFrom,
        onToggleLock = session.onToggleLock,
        onBeginDrift = session.onBeginDrift,
        onPinAnchor = session.onPinAnchor,
        onApplyDrift = session.onApplyDrift,
        onCancelDrift = session.onCancelDrift,
        onUndo = session.onUndo,
        onSave = {
            problem = null
            session.onSave()
        },
        onLeave = { guard.leave { router.navigate(book, force = true) } },
        problem = problem,
    )

    guard.Dialog()
}

/** `sel=9,10` → the selected chapter numbers; junk entries are dropped rather than crashing. */
internal fun parseSelection(raw: String?): Set<Int> =
    raw
        .orEmpty()
        .split(',')
        .mapNotNull { it.trim().toIntOrNull() }
        .toSet()
