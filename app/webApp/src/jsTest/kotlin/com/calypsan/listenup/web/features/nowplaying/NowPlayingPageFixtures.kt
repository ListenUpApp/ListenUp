package com.calypsan.listenup.web.features.nowplaying

import com.calypsan.listenup.client.playback.PlayerPhase
import com.calypsan.listenup.client.presentation.player.FinishedInfo
import com.calypsan.listenup.client.presentation.player.PlayerChapterRow
import com.calypsan.listenup.client.presentation.player.PlayerContributor
import com.calypsan.listenup.client.presentation.player.PlayerSeriesRef
import com.calypsan.listenup.client.presentation.player.PlayerUiState
import com.calypsan.listenup.client.presentation.player.PrimaryControl

/** The Way of Kings' length: 45h 30m. */
internal const val KINGS_MS = 163_800_000L

/** The Way of Kings as the shared model hands it to the Now Playing page: a known author, one series. */
@Suppress("LongParameterList") // one knob per thing a spec varies
internal fun wayOfKings(
    bookId: String = "b1",
    coverHash: String? = "abc123",
    authors: List<PlayerContributor> = listOf(PlayerContributor("c1", "Brandon Sanderson", 1)),
    narrators: List<PlayerContributor> = listOf(PlayerContributor("n1", "Michael Kramer", 1)),
    series: List<PlayerSeriesRef> = listOf(PlayerSeriesRef("s1", "The Stormlight Archive", "1")),
    phase: PlayerPhase = PlayerPhase.Playing,
    primary: PrimaryControl? = null,
    chapters: List<PlayerChapterRow> = emptyList(),
    currentChapterIndex: Int? = null,
    boostDb: Float = 0f,
    firstPdfDocId: String? = null,
    finished: FinishedInfo? = null,
    genericTitles: Boolean = false,
): PlayerUiState.Loaded {
    val base =
        loadedPlayerState(
            title = "The Way of Kings",
            bookId = bookId,
            phase = phase,
            authors = authors,
            narrators = narrators,
            series = series,
            coverHash = coverHash,
            durationMs = KINGS_MS,
            chapters = chapters,
            currentChapterIndex = currentChapterIndex,
            boostDb = boostDb,
            firstPdfDocId = firstPdfDocId,
            finished = finished,
            genericTitles = genericTitles,
        )
    return primary?.let { base.copy(primary = it) } ?: base
}
