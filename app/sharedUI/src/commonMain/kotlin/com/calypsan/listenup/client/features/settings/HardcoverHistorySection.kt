package com.calypsan.listenup.client.features.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.TaskAlt
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.calypsan.listenup.api.dto.hardcover.HardcoverHistory
import com.calypsan.listenup.client.design.components.SettingRow
import com.calypsan.listenup.client.design.components.TonalIconTile
import com.calypsan.listenup.client.design.components.listenUpOutlinedBorder
import com.calypsan.listenup.client.design.haptics.LocalHaptics
import com.calypsan.listenup.client.design.theme.Spacing
import com.calypsan.listenup.client.design.theme.extendedColors
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.common_dismiss
import listenup.composeapp.generated.resources.hardcover_history_needs_match
import listenup.composeapp.generated.resources.hardcover_history_needs_match_one
import listenup.composeapp.generated.resources.hardcover_history_none_sent
import listenup.composeapp.generated.resources.hardcover_history_none_sent_one
import listenup.composeapp.generated.resources.hardcover_history_not_now
import listenup.composeapp.generated.resources.hardcover_history_offer_body
import listenup.composeapp.generated.resources.hardcover_history_offer_body_one
import listenup.composeapp.generated.resources.hardcover_history_offer_title
import listenup.composeapp.generated.resources.hardcover_history_progress_label
import listenup.composeapp.generated.resources.hardcover_history_progress_value
import listenup.composeapp.generated.resources.hardcover_history_row_detail
import listenup.composeapp.generated.resources.hardcover_history_row_detail_one
import listenup.composeapp.generated.resources.hardcover_history_row_send
import listenup.composeapp.generated.resources.hardcover_history_row_send_label
import listenup.composeapp.generated.resources.hardcover_history_row_title
import listenup.composeapp.generated.resources.hardcover_history_send
import listenup.composeapp.generated.resources.hardcover_history_send_one
import listenup.composeapp.generated.resources.hardcover_history_sending
import listenup.composeapp.generated.resources.hardcover_history_sending_note
import listenup.composeapp.generated.resources.hardcover_history_sending_one
import listenup.composeapp.generated.resources.hardcover_history_sent
import listenup.composeapp.generated.resources.hardcover_history_sent_all
import listenup.composeapp.generated.resources.hardcover_history_sent_one
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/** The narrowest card content that holds Not now and Send side by side; narrower, they stack, Send first. */
private val SideBySideMinWidth = 300.dp
private val HistoryTileSize = 40.dp
private val ActionMinHeight = 48.dp

/**
 * The card that sends the books finished before connecting (#1540), between who you are and Sync: the
 * offer, then the send in progress, then what it came to. Nothing for None or Available — Available is the
 * quiet row in Sync ([HardcoverEarlierBooksRow]). [onShowNeedsMatch] brings the Needs a match section into
 * view from Done's "4 need a match".
 */
@Composable
internal fun HardcoverHistoryCard(
    history: HardcoverHistory,
    onSend: () -> Unit,
    onDismiss: () -> Unit,
    onShowNeedsMatch: () -> Unit,
) {
    when (history) {
        is HardcoverHistory.Offer -> {
            OfferCard(books = history.bookCount, onSend = onSend, onNotNow = onDismiss)
        }

        is HardcoverHistory.Sending -> {
            SendingCard(sent = history.sentBooks, total = history.totalBooks)
        }

        is HardcoverHistory.Done -> {
            DoneCard(
                sent = history.sentBooks,
                needsMatch = history.needsMatchBooks,
                onDismiss = onDismiss,
                onShowNeedsMatch = onShowNeedsMatch,
            )
        }

        HardcoverHistory.None, is HardcoverHistory.Available -> {}
    }
}

/**
 * "Send earlier books": the quiet row in Sync after "Not now", while earlier books wait. It sends in place
 * — an outlined Send, never a chevron, which would promise another screen.
 */
@Composable
internal fun HardcoverEarlierBooksRow(
    books: Int,
    onSend: () -> Unit,
) {
    val haptics = LocalHaptics.current
    val sendLabel = stringResource(Res.string.hardcover_history_row_send_label)
    SettingRow(
        title = stringResource(Res.string.hardcover_history_row_title),
        subtitle = counted(books, Res.string.hardcover_history_row_detail_one, Res.string.hardcover_history_row_detail),
        leading = {
            TonalIconTile(
                icon = Icons.Outlined.History,
                size = HistoryTileSize,
                accent = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        trailing = {
            OutlinedButton(
                border = listenUpOutlinedBorder(),
                onClick = {
                    haptics.press()
                    onSend()
                },
                modifier = Modifier.heightIn(min = ActionMinHeight).semantics { contentDescription = sendLabel },
            ) {
                Text(text = stringResource(Res.string.hardcover_history_row_send), fontWeight = FontWeight.SemiBold)
            }
        },
    )
}

@Composable
private fun HistoryCardSurface(content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
            content = content,
        )
    }
}

@Composable
private fun OfferCard(
    books: Int,
    onSend: () -> Unit,
    onNotNow: () -> Unit,
) {
    val haptics = LocalHaptics.current
    HistoryCardSurface {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            TonalIconTile(icon = Icons.Outlined.History, size = HistoryTileSize)
            Text(
                text = stringResource(Res.string.hardcover_history_offer_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.semantics { heading() },
            )
        }
        Text(
            text = counted(books, Res.string.hardcover_history_offer_body_one, Res.string.hardcover_history_offer_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        val send: @Composable (Modifier) -> Unit = { modifier ->
            FilledTonalButton(
                onClick = {
                    haptics.press()
                    onSend()
                },
                modifier = modifier.heightIn(min = ActionMinHeight),
                colors =
                    ButtonDefaults.filledTonalButtonColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    ),
            ) {
                Text(
                    text = counted(books, Res.string.hardcover_history_send_one, Res.string.hardcover_history_send),
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
        val notNow: @Composable (Modifier) -> Unit = { modifier ->
            TextButton(
                onClick = {
                    haptics.press()
                    onNotNow()
                },
                modifier = modifier.heightIn(min = ActionMinHeight),
            ) {
                Text(text = stringResource(Res.string.hardcover_history_not_now), fontWeight = FontWeight.SemiBold)
            }
        }
        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            if (maxWidth >= SideBySideMinWidth) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.sm, Alignment.End),
                ) {
                    notNow(Modifier)
                    send(Modifier)
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                    send(Modifier.fillMaxWidth())
                    notNow(Modifier.fillMaxWidth())
                }
            }
        }
    }
}

@Composable
private fun SendingCard(
    sent: Int,
    total: Int,
) {
    val label = stringResource(Res.string.hardcover_history_progress_label)
    val value = stringResource(Res.string.hardcover_history_progress_value, sent, total)
    HistoryCardSurface {
        Row(
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            TonalIconTile(icon = Icons.Outlined.History, size = HistoryTileSize)
            Text(
                text =
                    if (total == 1) {
                        stringResource(Res.string.hardcover_history_sending_one)
                    } else {
                        stringResource(Res.string.hardcover_history_sending, sent, total)
                    },
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
        }
        LinearProgressIndicator(
            progress = { if (total == 0) 0f else sent.toFloat() / total },
            modifier =
                Modifier.fillMaxWidth().semantics {
                    contentDescription = label
                    stateDescription = value
                },
        )
        Text(
            text = stringResource(Res.string.hardcover_history_sending_note),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun DoneCard(
    sent: Int,
    needsMatch: Int,
    onDismiss: () -> Unit,
    onShowNeedsMatch: () -> Unit,
) {
    val haptics = LocalHaptics.current
    HistoryCardSurface {
        Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            TonalIconTile(
                icon = Icons.Outlined.TaskAlt,
                size = HistoryTileSize,
                accent = MaterialTheme.extendedColors.success,
            )
            val announced = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
            Column(modifier = Modifier.weight(1f).padding(top = Spacing.sm).then(announced)) {
                Text(
                    text = sentLine(sent, needsMatch),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                if (needsMatch > 0) {
                    TextButton(
                        onClick = onShowNeedsMatch,
                        modifier = Modifier.heightIn(min = ActionMinHeight),
                        contentPadding = PaddingValues(horizontal = 0.dp),
                    ) {
                        Text(
                            text =
                                counted(
                                    needsMatch,
                                    Res.string.hardcover_history_needs_match_one,
                                    Res.string.hardcover_history_needs_match,
                                ),
                            textDecoration = TextDecoration.Underline,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
            }
            IconButton(
                onClick = {
                    haptics.press()
                    onDismiss()
                },
            ) {
                Icon(imageVector = Icons.Outlined.Close, contentDescription = stringResource(Res.string.common_dismiss))
            }
        }
    }
}

/**
 * "Sent 70 books", "Sent all 74 books" when none wait for a match, "Sent 1 book" — or, when nothing could be
 * sent yet, "4 books need a match before they can be sent".
 */
@Composable
private fun sentLine(
    sent: Int,
    needsMatch: Int,
): String =
    when {
        sent == 0 && needsMatch > 0 -> {
            counted(needsMatch, Res.string.hardcover_history_none_sent_one, Res.string.hardcover_history_none_sent)
        }

        sent == 1 -> {
            stringResource(Res.string.hardcover_history_sent_one)
        }

        needsMatch == 0 && sent > 1 -> {
            stringResource(Res.string.hardcover_history_sent_all, sent)
        }

        else -> {
            stringResource(Res.string.hardcover_history_sent, sent)
        }
    }

/** [one] for a count of one, else [many] with the count. */
@Composable
private fun counted(
    count: Int,
    one: StringResource,
    many: StringResource,
): String = if (count == 1) stringResource(one) else stringResource(many, count)
