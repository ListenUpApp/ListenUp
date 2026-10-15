package com.calypsan.listenup.client.features.nowplaying

import androidx.compose.runtime.Composable
import com.calypsan.listenup.client.domain.model.BookContributor
import com.calypsan.listenup.client.playback.PlayerPhase
import com.calypsan.listenup.client.presentation.player.PlayerBook
import com.calypsan.listenup.client.presentation.player.PlayerContributor
import com.calypsan.listenup.client.presentation.player.PlayerUiState
import com.calypsan.listenup.client.presentation.player.PrimaryControl
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.player_chapter_number
import org.jetbrains.compose.resources.stringResource

/** Audio is advancing: the wave moves and the scrubber animates. */
internal val PlayerUiState.Loaded.isAudible: Boolean
    get() = phase == PlayerPhase.Playing || phase == PlayerPhase.SleepFading

/** Preparing or buffering: the main button spins and the scrubber holds still. */
internal val PlayerUiState.Loaded.isBusy: Boolean
    get() = primary == PrimaryControl.Busy

/** The byline as one line ("A, B"), for cover placeholders. */
internal val PlayerBook.authorLine: String
    get() = authors.joinToString(", ") { it.name }

/** The chapter's own title, or "Chapter N" when it has none (a chapterless book reads "Chapter 1", as before). */
@Composable
internal fun PlayerUiState.Loaded.chapterLine(): String =
    chapter.title.takeUnless { chapter.isWholeBook }?.takeIf { it.isNotBlank() }
        ?: stringResource(Res.string.player_chapter_number, chapter.number)

/** The shared contributor rows and full-cast sheet take [BookContributor]s. */
internal fun List<PlayerContributor>.asBookContributors(): List<BookContributor> =
    map { BookContributor(id = it.id, name = it.name) }
