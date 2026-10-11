package com.calypsan.listenup.web.features.seriesdetail

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import com.calypsan.listenup.client.presentation.seriesedit.SeriesEditNavAction
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.web.features.nowplaying.PlaybackSession
import com.calypsan.listenup.web.features.seriesedit.OpenSeriesEdit
import com.calypsan.listenup.web.features.seriesedit.SeriesEditPage
import com.calypsan.listenup.web.nav.Router
import com.calypsan.listenup.web.nav.Routes
import com.calypsan.listenup.web.nav.rememberSession
import com.calypsan.listenup.web.shell.RouteContent

/**
 * The `/series/{id}` branch of [RouteContent], detail and form together.
 *
 * ⛔ The form is tested first. `/series/{id}` is a prefix of `/series/{id}/edit`, so a branch order
 * that asks "is this a series?" before "is this the form over one?" makes the form unreachable by
 * link — which is how it has to be ordered wherever these two live.
 */
@Composable
internal fun SeriesRouteContent(
    seriesId: String,
    editingSeriesId: String?,
    router: Router,
    openSeriesDetail: OpenSeriesDetail,
    openSeriesEdit: OpenSeriesEdit,
    playback: PlaybackSession,
) {
    if (editingSeriesId != null) {
        SeriesEditRoute(router = router, openSeriesEdit = openSeriesEdit, seriesId = editingSeriesId)
        return
    }

    val session = seriesDetailSession(seriesId, openSeriesDetail)
    SeriesDetailPage(
        state = session.state.collectAsState().value,
        onEdit = { router.navigate(Routes.seriesEdit(seriesId)) },
        onOpenLibrary = { router.navigate(Routes.library) },
        onOpenBook = { id -> router.navigate(Routes.Book.detail(id)) },
        onPlayBook = { id -> playback.onPlayBook(BookId(id)) },
        // Pushed, not popped to: Back from the ancestor still returns to this series.
        onOpenSeries = { id -> router.navigate(Routes.series(id)) },
        onToggleSection = session.onToggleSection,
        addSubSeries = session.addSubSeries.collectAsState().value,
        onAddSubSeriesEvent = session.onAddSubSeriesEvent,
    )
}

/**
 * Opens a Series Edit session for [seriesId], collects it, and answers the navigation it asks for.
 */
@Composable
private fun SeriesEditRoute(
    router: Router,
    openSeriesEdit: OpenSeriesEdit,
    seriesId: String,
) {
    val session = rememberSession(seriesId) { openSeriesEdit(seriesId) }

    LaunchedEffect(session) {
        session.navActions.collect { action ->
            when (action) {
                // A saved form and a cancelled one both leave the same way — this ViewModel has no
                // separate SaveSuccess, so the series' own page is where both land.
                SeriesEditNavAction.NavigateBack -> {
                    router.navigate(Routes.series(seriesId))
                }

                // ⛔ `replace`, not `navigate`. The merge deletes the series being edited, so a
                // pushed entry would send Back to the editor of a series that no longer exists.
                is SeriesEditNavAction.NavigateToMerged -> {
                    router.replace(Routes.series(action.seriesId.value))
                }
            }
        }
    }

    SeriesEditPage(
        state = session.state.collectAsState().value,
        mergeCandidates = session.mergeCandidates.collectAsState().value,
        mergeHistory = session.mergeHistory.collectAsState().value,
        onEvent = session.onEvent,
        onMergeQuery = session.onMergeQuery,
        parentPickerRows = session.parentPickerRows.collectAsState().value,
        addSubSeries = session.addSubSeries.collectAsState().value,
        onAddSubSeriesEvent = session.onAddSubSeriesEvent,
    )
}

/**
 * Opens a Series Detail session for [seriesId] and collects it.
 */
@Composable
private fun seriesDetailSession(
    seriesId: String,
    openSeriesDetail: OpenSeriesDetail,
): SeriesDetailSession {
    val session = rememberSession(seriesId) { openSeriesDetail(seriesId) }
    return session
}
