package com.calypsan.listenup.client.features.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.calypsan.listenup.client.core.DurationFormatter
import com.calypsan.listenup.client.design.components.BookCoverImage
import com.calypsan.listenup.client.design.components.RestrictedBookMarker
import com.calypsan.listenup.client.design.components.SectionTitle
import com.calypsan.listenup.client.design.theme.ContentShapes
import com.calypsan.listenup.client.design.theme.Spacing
import com.calypsan.listenup.client.domain.model.NextInSeries
import com.calypsan.listenup.client.presentation.home.HomeSlot
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.common_discover
import listenup.composeapp.generated.resources.common_play
import listenup.composeapp.generated.resources.home_browse_library
import listenup.composeapp.generated.resources.home_getting_started_a11y
import listenup.composeapp.generated.resources.home_next_in_a_series
import listenup.composeapp.generated.resources.home_nothing_in_progress
import listenup.composeapp.generated.resources.home_series_book_length
import listenup.composeapp.generated.resources.home_series_length
import listenup.composeapp.generated.resources.home_welcome_body
import listenup.composeapp.generated.resources.home_welcome_headline
import listenup.composeapp.generated.resources.library_card_read_by
import org.jetbrains.compose.resources.stringResource
import kotlin.time.Duration.Companion.milliseconds

private val CandidateCover = 96.dp
private val WelcomeCover = 72.dp
private val PrimaryHeight = 56.dp
private const val WELCOME_COVERS = 4

/**
 * Nothing in progress, but the reader has listened before (spec §2.3): with a series candidate, its card with
 * **Play** and **Browse the Library**; without one, Browse the Library and Discover (H17).
 */
@Composable
internal fun HomeNothingInProgress(
    slot: HomeSlot.NothingInProgress,
    actions: HomeActions,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Spacing.titleGap)) {
        SectionTitle(title = stringResource(Res.string.home_nothing_in_progress))
        val candidate = slot.candidate
        if (candidate == null) {
            QuietButtons(primary = null, actions = actions)
        } else {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.extraLarge,
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
            ) {
                Column(Modifier.padding(Spacing.lg), verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
                    CandidateIdentity(candidate, onOpen = { actions.onOpenBook(candidate.bookId) })
                    QuietButtons(primary = { actions.onPlay(candidate.bookId) }, actions = actions)
                }
            }
        }
    }
}

/** Cover, "Next in a series you're reading", title, narrators and "Stormlight Archive, Book 2 · 48h 15m": one link. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun CandidateIdentity(
    next: NextInSeries,
    onOpen: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().clip(MaterialTheme.shapes.large).clickable(onClick = onOpen),
        horizontalArrangement = Arrangement.spacedBy(Spacing.lg),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box {
            BookCoverImage(
                bookId = next.bookId,
                coverPath = next.coverPath,
                coverHash = next.coverHash,
                contentDescription = null,
                title = next.title,
                // Decorative: the link's text names the book.
                modifier = Modifier.size(CandidateCover).clip(ContentShapes.card).clearAndSetSemantics {},
            )
            RestrictedBookMarker(next.bookId, Modifier.align(Alignment.TopStart).padding(Spacing.sm))
        }
        Column {
            Text(
                text = stringResource(Res.string.home_next_in_a_series),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = next.title,
                style = MaterialTheme.typography.titleLargeEmphasized,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            next.narratorNames?.takeIf { it.isNotBlank() }?.let { narrators ->
                Text(
                    text = stringResource(Res.string.library_card_read_by, narrators),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = seriesLine(next),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun seriesLine(next: NextInSeries): String {
    val length = DurationFormatter.hoursMinutes(next.durationMs.milliseconds)
    return next.sequenceLabel?.let { sequence ->
        stringResource(Res.string.home_series_book_length, next.seriesName, sequence, length)
    } ?: stringResource(Res.string.home_series_length, next.seriesName, length)
}

/**
 * No listening history at all (spec §2.3 NewListener): four of the newest covers, "Pick something new", and the way
 * in — Browse the Library (primary) and Discover. Never a count of the library (Simon, 2026-10-14).
 */
@Composable
internal fun HomeWelcome(
    slot: HomeSlot.NewListener,
    actions: HomeActions,
    modifier: Modifier = Modifier,
) {
    val label = stringResource(Res.string.home_getting_started_a11y)
    Column(
        modifier =
            modifier.fillMaxWidth().semantics {
                isTraversalGroup = true
                contentDescription = label
            },
        verticalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            val covers = slot.covers.take(WELCOME_COVERS)
            covers.forEach { cover ->
                BookCoverImage(
                    bookId = cover.bookId,
                    coverPath = cover.coverPath,
                    coverHash = cover.coverHash,
                    contentDescription = null,
                    modifier = Modifier.size(WelcomeCover).clip(ContentShapes.card).clearAndSetSemantics {},
                )
            }
            repeat(WELCOME_COVERS - covers.size) {
                Box(
                    Modifier
                        .size(WelcomeCover)
                        .clip(ContentShapes.card)
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                )
            }
        }
        Text(
            text = stringResource(Res.string.home_welcome_headline),
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = stringResource(Res.string.home_welcome_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm), verticalAlignment = Alignment.CenterVertically) {
            PrimaryButton(stringResource(Res.string.home_browse_library), actions.onOpenLibrary)
            FilledTonalButton(onClick = actions.onOpenDiscover, modifier = Modifier.heightIn(min = PrimaryHeight)) {
                Text(stringResource(Res.string.common_discover))
            }
        }
    }
}

/**
 * The quiet slots' buttons, a standard group (never connected, spec §2.4): [primary] plays the candidate beside a
 * tonal Browse the Library; without one, Browse the Library and Discover, both tonal.
 */
@Composable
private fun QuietButtons(
    primary: (() -> Unit)?,
    actions: HomeActions,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm), verticalAlignment = Alignment.CenterVertically) {
        primary?.let { PrimaryButton(stringResource(Res.string.common_play), it) }
        FilledTonalButton(onClick = actions.onOpenLibrary, modifier = Modifier.heightIn(min = PrimaryHeight)) {
            Text(stringResource(Res.string.home_browse_library))
        }
        if (primary == null) {
            FilledTonalButton(onClick = actions.onOpenDiscover, modifier = Modifier.heightIn(min = PrimaryHeight)) {
                Text(stringResource(Res.string.common_discover))
            }
        }
    }
}

/** The 56dp primary pill (spec §2.4). */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
) {
    Button(onClick = onClick, shapes = ButtonDefaults.shapes(), modifier = Modifier.heightIn(min = PrimaryHeight)) {
        Text(text)
    }
}
