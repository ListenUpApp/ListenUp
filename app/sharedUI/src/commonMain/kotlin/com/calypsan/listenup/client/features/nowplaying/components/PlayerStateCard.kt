package com.calypsan.listenup.client.features.nowplaying.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ButtonGroup
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.calypsan.listenup.client.design.MinTouchTarget
import com.calypsan.listenup.client.design.components.BookCoverImage
import com.calypsan.listenup.client.design.components.RatingStars
import com.calypsan.listenup.client.design.theme.Spacing
import com.calypsan.listenup.client.design.theme.extendedColors
import com.calypsan.listenup.client.domain.model.NextInSeries
import com.calypsan.listenup.client.features.home.finishedDaysAgo
import com.calypsan.listenup.client.features.home.finishedEyebrow
import com.calypsan.listenup.client.features.home.finishedMeta
import com.calypsan.listenup.client.presentation.error.playerCause
import com.calypsan.listenup.client.presentation.error.playerTitle
import com.calypsan.listenup.client.presentation.player.PlayerCard
import com.calypsan.listenup.client.presentation.player.PlayerCardAction
import com.calypsan.listenup.client.presentation.player.PlayerCardTone
import kotlinx.datetime.TimeZone
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.home_how_was_it
import listenup.composeapp.generated.resources.home_next_in_series
import listenup.composeapp.generated.resources.home_play_next_book
import listenup.composeapp.generated.resources.player_close_book
import listenup.composeapp.generated.resources.player_mini_keep_listening
import listenup.composeapp.generated.resources.player_mini_listen_again
import listenup.composeapp.generated.resources.player_mini_open_inbox
import listenup.composeapp.generated.resources.player_mini_resume
import listenup.composeapp.generated.resources.player_np_dismiss
import listenup.composeapp.generated.resources.player_np_next_in_series_book
import listenup.composeapp.generated.resources.player_np_sign_in
import listenup.composeapp.generated.resources.player_place_saved
import listenup.composeapp.generated.resources.player_play
import listenup.composeapp.generated.resources.player_reference
import listenup.composeapp.generated.resources.player_retry
import listenup.composeapp.generated.resources.player_sheet_fading_body
import listenup.composeapp.generated.resources.player_sheet_fading_title
import org.jetbrains.compose.resources.stringResource
import kotlin.time.Clock

/** The next book's cover: square, like every cover in the player (F4). */
private val NextCover = 56.dp

/**
 * The card that takes the transport's place (spec P32, §3.7, §4.6, §5.6): a problem with its ways out, the finished
 * book, or the sleep timer's fade. The actions are decided in shared code ([com.calypsan.listenup.client.presentation.player.PlayerCards]); this
 * draws them. Flat, on a container chosen by tone (m3.material.io/styles/color/roles), 28dp corners, no shadow (F6).
 */
@Composable
fun PlayerStateCard(
    card: PlayerCard,
    onAction: (PlayerCardAction) -> Unit,
    onRate: (Int) -> Unit,
    modifier: Modifier = Modifier,
    nowMs: Long = Clock.System.now().toEpochMilliseconds(),
    zone: TimeZone = TimeZone.currentSystemDefault(),
) {
    when (card) {
        is PlayerCard.Problem -> {
            ProblemCard(card = card, onAction = onAction, modifier = modifier)
        }

        is PlayerCard.Finished -> {
            FinishedCard(
                card = card,
                onAction = onAction,
                onRate = onRate,
                nowMs = nowMs,
                zone = zone,
                modifier = modifier,
            )
        }

        PlayerCard.SleepFading -> {
            SleepFadingCard(onKeepListening = { onAction(PlayerCardAction.KeepListening) }, modifier = modifier)
        }
    }
}

/**
 * The sleep timer's fade (§5.6 "Sleep fading", `States-Android`): the sleep gold, `tertiaryContainer`, with the moon,
 * "Fading out…", what is happening, and one button, Keep listening, on `tertiary`. Announced politely: nothing failed.
 * Now Playing draws it in the transport's place; the sleep sheet draws the same card while the fade runs (PT7).
 */
@Composable
internal fun SleepFadingCard(
    onKeepListening: () -> Unit,
    modifier: Modifier = Modifier,
) {
    FlatCard(
        container = MaterialTheme.colorScheme.tertiaryContainer,
        ink = MaterialTheme.colorScheme.onTertiaryContainer,
        modifier = modifier,
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
        ) {
            Icon(Icons.Default.Bedtime, contentDescription = null)
            Text(stringResource(Res.string.player_sheet_fading_title), style = MaterialTheme.typography.titleMedium)
        }
        Text(stringResource(Res.string.player_sheet_fading_body), style = MaterialTheme.typography.bodyMedium)
        Button(
            onClick = onKeepListening,
            shapes = ButtonDefaults.shapes(),
            colors =
                ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.tertiary,
                    contentColor = MaterialTheme.colorScheme.onTertiary,
                ),
            modifier = Modifier.heightIn(min = MinTouchTarget),
        ) { Text(PlayerCardAction.KeepListening.label()) }
    }
}

@Composable
private fun ProblemCard(
    card: PlayerCard.Problem,
    onAction: (PlayerCardAction) -> Unit,
    modifier: Modifier,
) {
    val (container, ink) = card.tone.colors()
    FlatCard(container = container, ink = ink, modifier = modifier) {
        Text(
            card.error.playerTitle(),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive },
        )
        card.error.playerCause()?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
        if (card.placeSaved) {
            Text(stringResource(Res.string.player_place_saved), style = MaterialTheme.typography.bodyMedium)
        }
        card.error.correlationId?.let {
            Text(stringResource(Res.string.player_reference, it), style = MaterialTheme.typography.labelSmall)
        }
        CardActions(card.primaryAction, card.secondaryAction, onAction)
    }
}

@Composable
private fun FinishedCard(
    card: PlayerCard.Finished,
    onAction: (PlayerCardAction) -> Unit,
    onRate: (Int) -> Unit,
    nowMs: Long,
    zone: TimeZone,
    modifier: Modifier,
) {
    val info = card.info
    FlatCard(
        container = MaterialTheme.colorScheme.surfaceContainer,
        ink = MaterialTheme.colorScheme.onSurface,
        modifier = modifier,
    ) {
        val meta =
            finishedMeta(
                durationMs = info.durationMs,
                startedAtMs = info.startedAtMs,
                finishedAtMs = info.finishedAtMs ?: nowMs,
                zone = zone,
            )
        val line =
            info.finishedAtMs?.let { "${finishedEyebrow(finishedDaysAgo(it, nowMs, zone))} · $meta" } ?: meta
        Text(
            text = line,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
        FinishedRating(yourHalfStars = info.yourHalfStars, onRate = onRate)
        info.nextBook?.let { NextBookRow(it) }
        CardActions(card.primaryAction, card.secondaryAction, onAction)
    }
}

@Composable
private fun FlatCard(
    container: Color,
    ink: Color,
    modifier: Modifier,
    content: @Composable () -> Unit,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(containerColor = container, contentColor = ink),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(Modifier.padding(Spacing.lg), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) { content() }
    }
}

/** "How was it?" over the shared half-star control; a drag previews, a settle rates (Home's pattern). */
@Composable
private fun FinishedRating(
    yourHalfStars: Int?,
    onRate: (Int) -> Unit,
) {
    var preview by remember(yourHalfStars) { mutableStateOf(yourHalfStars ?: 0) }
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        Text(stringResource(Res.string.home_how_was_it), style = MaterialTheme.typography.titleSmall)
        RatingStars(
            halfStars = preview,
            onHalfStarsChange = { preview = it },
            onHalfStarsCommit = onRate,
        )
    }
}

/** "Next in the series": a square cover, the title, and its place in the series. */
@Composable
private fun NextBookRow(next: NextInSeries) {
    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.md), verticalAlignment = Alignment.CenterVertically) {
        BookCoverImage(
            bookId = next.bookId,
            coverPath = next.coverPath,
            coverHash = next.coverHash,
            contentDescription = null,
            title = next.title,
            // Decorative: the text names the book.
            modifier =
                Modifier
                    .size(NextCover)
                    .aspectRatio(1f)
                    .clip(MaterialTheme.shapes.small)
                    .clearAndSetSemantics {},
        )
        Column {
            Text(
                stringResource(Res.string.home_next_in_series),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = next.title,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            next.sequenceLabel?.let { sequence ->
                Text(
                    stringResource(Res.string.player_np_next_in_series_book, sequence),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun PlayerCardTone.colors(): Pair<Color, Color> =
    when (this) {
        PlayerCardTone.Error -> {
            MaterialTheme.colorScheme.errorContainer to MaterialTheme.colorScheme.onErrorContainer
        }

        PlayerCardTone.Held -> {
            MaterialTheme.extendedColors.heldContainer to
                MaterialTheme.extendedColors.onHeldContainer
        }

        PlayerCardTone.Neutral -> {
            MaterialTheme.colorScheme.surfaceContainerHigh to MaterialTheme.colorScheme.onSurface
        }
    }

/** The card's buttons: the primary filled, the secondary tonal, one standard group (§6 Groups). */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun CardActions(
    primary: PlayerCardAction,
    secondary: PlayerCardAction?,
    onAction: (PlayerCardAction) -> Unit,
) {
    val primaryLabel = primary.label()
    val secondaryLabel = secondary?.label()
    ButtonGroup(
        overflowIndicator = {},
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        customItem(
            buttonGroupContent = {
                Button(
                    onClick = { onAction(primary) },
                    shapes = ButtonDefaults.shapes(),
                    modifier = Modifier.heightIn(min = MinTouchTarget),
                ) { Text(primaryLabel) }
            },
            menuContent = {},
        )
        if (secondary != null && secondaryLabel != null) {
            customItem(
                buttonGroupContent = {
                    FilledTonalButton(
                        onClick = { onAction(secondary) },
                        shapes = ButtonDefaults.shapes(),
                        modifier = Modifier.heightIn(min = MinTouchTarget),
                    ) { Text(secondaryLabel) }
                },
                menuContent = {},
            )
        }
    }
}

@Composable
private fun PlayerCardAction.label(): String =
    stringResource(
        when (this) {
            PlayerCardAction.Retry -> Res.string.player_retry
            PlayerCardAction.Dismiss -> Res.string.player_np_dismiss
            PlayerCardAction.CloseBook -> Res.string.player_close_book
            PlayerCardAction.SignIn -> Res.string.player_np_sign_in
            PlayerCardAction.OpenInbox -> Res.string.player_mini_open_inbox
            PlayerCardAction.Resume -> Res.string.player_mini_resume
            PlayerCardAction.TapToPlay -> Res.string.player_play
            PlayerCardAction.ListenAgain -> Res.string.player_mini_listen_again
            PlayerCardAction.PlayNextBook -> Res.string.home_play_next_book
            PlayerCardAction.KeepListening -> Res.string.player_mini_keep_listening
        },
    )
