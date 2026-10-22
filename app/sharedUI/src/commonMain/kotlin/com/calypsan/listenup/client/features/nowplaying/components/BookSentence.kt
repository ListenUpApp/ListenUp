package com.calypsan.listenup.client.features.nowplaying.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.calypsan.listenup.client.core.DurationFormatter
import com.calypsan.listenup.client.presentation.player.PlayerProgress
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.player_np_book_sentence
import org.jetbrains.compose.resources.stringResource
import kotlin.time.Duration.Companion.milliseconds

/** "Book 11% · 40h 31m left" (§3.1.2, §3.2): the book in words, content time, a progress leaf. */
@Composable
internal fun BookSentence(
    progress: () -> PlayerProgress,
    modifier: Modifier = Modifier,
) {
    val p = progress()
    Text(
        text =
            stringResource(
                Res.string.player_np_book_sentence,
                p.bookPercent,
                DurationFormatter.hoursMinutes(p.bookRemainingMs.milliseconds),
            ),
        style = MaterialTheme.typography.labelMedium.copy(fontFeatureSettings = "tnum"),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier,
    )
}
