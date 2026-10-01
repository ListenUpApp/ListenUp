package com.calypsan.listenup.client.features.settings

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.TaskAlt
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.calypsan.listenup.api.dto.hardcover.HardcoverHistory
import com.calypsan.listenup.api.dto.hardcover.HardcoverSyncProblem
import com.calypsan.listenup.client.design.components.BookCoverImage
import com.calypsan.listenup.client.design.components.CountBadge
import com.calypsan.listenup.client.design.components.ListenUpLoadingIndicatorSmall
import com.calypsan.listenup.client.design.components.SectionGroup
import com.calypsan.listenup.client.design.components.SectionSegment
import com.calypsan.listenup.client.design.components.SettingRow
import com.calypsan.listenup.client.design.components.TonalIconTile
import com.calypsan.listenup.client.design.haptics.LocalHaptics
import com.calypsan.listenup.client.design.theme.Spacing
import com.calypsan.listenup.client.design.util.relativeTime
import com.calypsan.listenup.client.presentation.hardcover.HardcoverBookToMatch
import com.calypsan.listenup.client.presentation.hardcover.HardcoverSyncStatus
import kotlinx.coroutines.delay
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.hardcover_find_on_hardcover
import listenup.composeapp.generated.resources.hardcover_last_synced
import listenup.composeapp.generated.resources.hardcover_last_synced_just_now
import listenup.composeapp.generated.resources.hardcover_needs_match_detail
import listenup.composeapp.generated.resources.hardcover_needs_match_none
import listenup.composeapp.generated.resources.hardcover_needs_match_section
import listenup.composeapp.generated.resources.hardcover_never_synced
import listenup.composeapp.generated.resources.hardcover_problem_pull_stalled
import listenup.composeapp.generated.resources.hardcover_problem_push_stalled
import listenup.composeapp.generated.resources.hardcover_problem_sync_now_failed
import listenup.composeapp.generated.resources.hardcover_sync_now
import listenup.composeapp.generated.resources.hardcover_sync_now_failed_notice
import listenup.composeapp.generated.resources.hardcover_sync_section
import listenup.composeapp.generated.resources.hardcover_syncing
import listenup.composeapp.generated.resources.hardcover_try_again
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import kotlin.time.Clock

private const val MINUTE_MS = 60_000L
private val StatusTileSize = 40.dp
private val NeedsMatchCoverWidth = 48.dp
private val NeedsMatchCoverHeight = 72.dp
private val ActionMinHeight = 48.dp

/** The failed Sync now, which the line itself doesn't show: the screen says it once, as a snackbar. */
private val HardcoverSyncStatus.isSyncNowFailure: Boolean
    get() = this is HardcoverSyncStatus.Problem && problem == HardcoverSyncProblem.SYNC_NOW_FAILED

/**
 * The sync line: when it last synced (re-read every minute, so "just now" ages honestly) beside
 * "Sync now", or "Syncing…" with Sync now held off while a sync the user asked for runs. A sync that
 * has stalled replaces the line with an amber card: the problem in plain words, and "Try again".
 *
 * A failed Sync now is brief, not a state of the screen: the line stays as it was, and
 * [HardcoverSyncNowFailedNotice] says it once.
 *
 * After "Not now" on the earlier-books offer, its quiet row follows the sync line ([HardcoverEarlierBooksRow]).
 */
@Composable
internal fun HardcoverSyncBlock(
    lastSyncedAt: Long?,
    sync: HardcoverSyncStatus,
    history: HardcoverHistory,
    onSyncNow: () -> Unit,
    onSendHistory: () -> Unit,
) {
    SectionGroup(label = stringResource(Res.string.hardcover_sync_section)) {
        if (sync is HardcoverSyncStatus.Problem && !sync.isSyncNowFailure) {
            StalledCard(problem = sync.problem, onTryAgain = onSyncNow)
        } else {
            SyncLine(
                lastSyncedAt = lastSyncedAt,
                isSyncing = sync == HardcoverSyncStatus.Syncing,
                onSyncNow = onSyncNow,
            )
        }
        if (history is HardcoverHistory.Available) {
            HardcoverEarlierBooksRow(books = history.bookCount, onSend = onSendHistory)
        }
    }
}

@Composable
private fun SyncLine(
    lastSyncedAt: Long?,
    isSyncing: Boolean,
    onSyncNow: () -> Unit,
) {
    var minuteTick by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(MINUTE_MS)
            minuteTick++
        }
    }
    key(minuteTick) {
        SettingRow(
            title = if (isSyncing) stringResource(Res.string.hardcover_syncing) else lastSyncedLine(lastSyncedAt),
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            leading = {
                if (isSyncing) {
                    Box(modifier = Modifier.size(StatusTileSize), contentAlignment = Alignment.Center) {
                        ListenUpLoadingIndicatorSmall()
                    }
                } else {
                    TonalIconTile(
                        icon = Icons.Outlined.Schedule,
                        size = StatusTileSize,
                        accent = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            trailing = { TonalAction(stringResource(Res.string.hardcover_sync_now), onSyncNow, enabled = !isSyncing) },
        )
    }
}

/** A push or pull that is stuck: amber and calm, never red — nothing is lost, and it keeps trying. */
@Composable
private fun StalledCard(
    problem: HardcoverSyncProblem,
    onTryAgain: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
        color = MaterialTheme.colorScheme.tertiaryContainer,
        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Icon(imageVector = Icons.Outlined.WarningAmber, contentDescription = null)
                Text(text = stringResource(problem.messageRes()), style = MaterialTheme.typography.bodyLarge)
            }
            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
                TonalAction(
                    text = stringResource(Res.string.hardcover_try_again),
                    onClick = onTryAgain,
                    containerColor = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = TINT_ALPHA),
                    contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                )
            }
        }
    }
}

private const val TINT_ALPHA = 0.1f

/**
 * Says a failed Sync now once, as a snackbar on [snackbarHostState] with "Try again" ([onTryAgain]):
 * Hardcover couldn't be reached, and nothing was lost. Draws nothing.
 */
@Composable
internal fun HardcoverSyncNowFailedNotice(
    sync: HardcoverSyncStatus,
    snackbarHostState: SnackbarHostState,
    onTryAgain: () -> Unit,
) {
    val failed = sync.isSyncNowFailure
    val message = stringResource(Res.string.hardcover_sync_now_failed_notice)
    val action = stringResource(Res.string.hardcover_try_again)
    val tryAgain by rememberUpdatedState(onTryAgain)
    LaunchedEffect(failed) {
        if (!failed) return@LaunchedEffect
        val result = snackbarHostState.showSnackbar(message, actionLabel = action, duration = SnackbarDuration.Long)
        if (result == SnackbarResult.ActionPerformed) tryAgain()
    }
}

/**
 * The books ListenUp couldn't match, counted, each with "Find on Hardcover". Once the list is known
 * to be empty it is one quiet line; before it is known ([isKnown] false) it claims nothing at all.
 */
@Composable
internal fun HardcoverNeedsMatch(
    books: List<HardcoverBookToMatch>,
    isKnown: Boolean,
    onFindMatch: (bookId: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!isKnown) return
    SectionGroup(
        label = stringResource(Res.string.hardcover_needs_match_section),
        modifier = modifier,
        trailing = { if (books.isNotEmpty()) CountBadge(count = books.size) },
    ) {
        if (books.isEmpty()) {
            QuietLine(icon = Icons.Outlined.TaskAlt, text = stringResource(Res.string.hardcover_needs_match_none))
        } else {
            Text(
                text = stringResource(Res.string.hardcover_needs_match_detail),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = Spacing.lg, end = Spacing.lg, bottom = Spacing.sm),
            )
            books.forEach { book -> NeedsMatchRow(book = book, onFindMatch = { onFindMatch(book.bookId) }) }
        }
    }
}

@Composable
private fun NeedsMatchRow(
    book: HardcoverBookToMatch,
    onFindMatch: () -> Unit,
) {
    val haptics = LocalHaptics.current
    SectionSegment {
        Row(
            modifier = Modifier.padding(horizontal = 20.dp, vertical = Spacing.lg),
            horizontalArrangement = Arrangement.spacedBy(Spacing.lg),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BookCoverImage(
                bookId = book.bookId,
                coverPath = book.coverPath,
                coverHash = book.coverHash,
                contentDescription = null,
                title = book.title,
                author = book.authorNames,
                modifier = Modifier.size(width = NeedsMatchCoverWidth, height = NeedsMatchCoverHeight),
            )
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(text = book.title, style = MaterialTheme.typography.titleMedium)
                if (book.authorNames.isNotBlank()) {
                    Text(
                        text = book.authorNames,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                OutlinedButton(
                    onClick = {
                        haptics.press()
                        onFindMatch()
                    },
                    modifier = Modifier.padding(top = 6.dp).heightIn(min = ActionMinHeight),
                    contentPadding = ButtonDefaults.ButtonWithIconContentPadding,
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Search,
                        contentDescription = null,
                        modifier = Modifier.size(ButtonDefaults.IconSize),
                    )
                    Text(
                        text = stringResource(Res.string.hardcover_find_on_hardcover),
                        modifier = Modifier.padding(start = ButtonDefaults.IconSpacing),
                    )
                }
            }
        }
    }
}

/** A single quiet sentence as a segment: a muted glyph and muted text. */
@Composable
internal fun QuietLine(
    icon: ImageVector,
    text: String,
) {
    SectionSegment {
        Row(
            modifier = Modifier.padding(horizontal = 20.dp, vertical = Spacing.lg),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(imageVector = icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                text = text,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** A filled-tonal action at least [ActionMinHeight] tall, with the press haptic. */
@Composable
private fun TonalAction(
    text: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    containerColor: Color = MaterialTheme.colorScheme.primaryContainer,
    contentColor: Color = MaterialTheme.colorScheme.onPrimaryContainer,
) {
    val haptics = LocalHaptics.current
    FilledTonalButton(
        onClick = {
            haptics.press()
            onClick()
        },
        enabled = enabled,
        modifier = Modifier.heightIn(min = ActionMinHeight),
        colors = ButtonDefaults.filledTonalButtonColors(containerColor = containerColor, contentColor = contentColor),
    ) {
        Text(text = text, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun lastSyncedLine(lastSyncedAt: Long?): String =
    when {
        lastSyncedAt == null -> {
            stringResource(Res.string.hardcover_never_synced)
        }

        Clock.System.now().toEpochMilliseconds() - lastSyncedAt < MINUTE_MS -> {
            stringResource(Res.string.hardcover_last_synced_just_now)
        }

        else -> {
            stringResource(Res.string.hardcover_last_synced, relativeTime(lastSyncedAt))
        }
    }

// Deliberately no `else`: a new problem must fail to compile here rather than borrow another's words.
private fun HardcoverSyncProblem.messageRes(): StringResource =
    when (this) {
        HardcoverSyncProblem.SYNC_NOW_FAILED -> Res.string.hardcover_problem_sync_now_failed
        HardcoverSyncProblem.PUSH_STALLED -> Res.string.hardcover_problem_push_stalled
        HardcoverSyncProblem.PULL_STALLED -> Res.string.hardcover_problem_pull_stalled
    }
