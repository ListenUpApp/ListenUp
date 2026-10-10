package com.calypsan.listenup.web.features.bulkedit

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import com.calypsan.listenup.client.presentation.bulkedit.BulkEditEvent
import com.calypsan.listenup.web.nav.Router
import com.calypsan.listenup.web.nav.Routes
import com.calypsan.listenup.web.nav.rememberSession

/**
 * Opens a Bulk Edit session over exactly [bookIds] and collects it.
 *
 * ⛔ Keyed on the ids. A bulk editor that could switch the books it edits is one keystroke away
 * from writing a publisher onto the wrong forty.
 *
 * Both outcomes navigate back to the library, and both say what happened — including the failure,
 * which names how many books were already committed. There is no rollback, so "it failed" without
 * that number leaves the reader unable to tell what state their library is in.
 */
@Composable
internal fun BulkEditRoute(
    router: Router,
    openBulkEdit: OpenBulkEdit,
    bookIds: List<String>,
    onToast: (String) -> Unit,
) {
    val key = bookIds.joinToString(",")
    val session = rememberSession(key) { openBulkEdit(bookIds) }

    val library = Routes.library

    LaunchedEffect(session) {
        session.events.collect { event ->
            when (event) {
                is BulkEditEvent.Applied -> onToast(appliedLabel(event.changedCount))
                is BulkEditEvent.Failed -> onToast(failedLabel(event.appliedCount))
            }
            router.navigate(library)
        }
    }

    BulkEditPage(
        state = session.state.collectAsState().value,
        catalog =
            BulkEditCatalog(
                genres = session.genres.collectAsState().value,
                tags = session.tags.collectAsState().value,
                moods = session.moods.collectAsState().value,
                seriesMatches = session.seriesMatches.collectAsState().value,
                contributorMatches = session.contributorMatches.collectAsState().value,
            ),
        actions =
            BulkEditActions(
                onSeriesQuery = session.onSeriesQuery,
                onContributorQuery = session.onContributorQuery,
                onPublisher = session.onPublisher,
                onYear = session.onYear,
                onLanguage = session.onLanguage,
                onSeries = session.onSeries,
                onContributors = session.onContributors,
                onGenres = session.onGenres,
                onTags = session.onTags,
                onMoods = session.onMoods,
                onApply = session.onApply,
                onLeave = { router.navigate(library) },
            ),
    )
}
