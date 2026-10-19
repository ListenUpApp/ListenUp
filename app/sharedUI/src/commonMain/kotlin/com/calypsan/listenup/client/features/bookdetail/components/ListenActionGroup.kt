package com.calypsan.listenup.client.features.bookdetail.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.PlaylistAdd
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SplitButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.calypsan.listenup.client.design.haptics.LocalHaptics
import com.calypsan.listenup.client.domain.model.BookDownloadStatus
import com.calypsan.listenup.client.features.bookdetail.DownloadSplitTrailingButton
import com.calypsan.listenup.client.features.bookdetail.DownloadLabelledButton
import com.calypsan.listenup.client.presentation.bookdetail.PrimaryAction
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.book_detail_add_to_shelf
import listenup.composeapp.generated.resources.book_detail_listen_again
import listenup.composeapp.generated.resources.book_detail_play
import listenup.composeapp.generated.resources.book_detail_preparing
import listenup.composeapp.generated.resources.book_detail_resume
import listenup.composeapp.generated.resources.book_detail_unavailable_offline
import org.jetbrains.compose.resources.stringResource

/**
 * Height of every button in the group: M3E size M (56 dp), the same Resume size as Home's hero, so the two screens
 * share one Resume. The rev 7 board drew 72 dp, which is not an M3E size.
 */
private val ActionHeight = 56.dp

/**
 * Book Detail's action row. On a tablet ([labelled]) it is an M3E **standard** button group (spec A15, M3E addendum):
 * Resume filled and dominant, Download and Add to shelf labelled tonal buttons. On a phone, Resume and Download are
 * one **split pair** as main had before the redesign (Simon, 2026-10-11): M3E split-button halves with small inner
 * corners and a 2 dp seam, the download half its own action (no chevron, no menu), and Add to shelf a separate tonal
 * icon button beside it. No caption sits under either (Simon, 2026-10-11): the download's size is in its accessible
 * name and in Details.
 */
@Suppress("LongParameterList")
@Composable
internal fun ListenActionGroup(
    primaryAction: PrimaryAction,
    isPreparing: Boolean,
    downloadStatus: BookDownloadStatus,
    isWaitingForWifi: Boolean,
    downloadSizeBytes: Long,
    supportsDownloads: Boolean,
    canDownload: Boolean,
    labelled: Boolean,
    onPrimaryClick: () -> Unit,
    onDownload: () -> Unit,
    onCancelDownload: () -> Unit,
    onRemoveDownload: () -> Unit,
    onAddToShelf: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptics = LocalHaptics.current
    val download = downloadActionFor(downloadStatus, isWaitingForWifi, downloadSizeBytes)
    val actions =
        ListenActions(
            label = primaryLabelFor(primaryAction, isPreparing),
            primaryEnabled = isPrimaryEnabled(primaryAction),
            download = download.takeIf { supportsDownloads },
            downloadEnabled = canDownload || download.tap != DownloadTap.Start,
            onPrimary = {
                haptics.press()
                onPrimaryClick()
            },
            onDownloadTap = { tap ->
                when (tap) {
                    DownloadTap.Start -> onDownload()
                    DownloadTap.Cancel -> onCancelDownload()
                    DownloadTap.Remove -> onRemoveDownload()
                }
            },
            onAddToShelf = onAddToShelf,
        )
    if (labelled) {
        TabletActionGroup(actions, modifier)
    } else {
        PhoneActionRow(actions, modifier)
    }
}

/** What both layouts draw: the primary's label and state, the download's (null where downloads are unsupported). */
private data class ListenActions(
    val label: PrimaryLabel,
    val primaryEnabled: Boolean,
    val download: DownloadAction?,
    val downloadEnabled: Boolean,
    val onPrimary: () -> Unit,
    val onDownloadTap: (DownloadTap) -> Unit,
    val onAddToShelf: () -> Unit,
)

/** Phone: Resume and Download joined as a split pair, Add to shelf apart. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun PhoneActionRow(
    actions: ListenActions,
    modifier: Modifier,
) {
    val shelfName = stringResource(Res.string.book_detail_add_to_shelf)
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val download = actions.download
        if (download != null) {
            Row(
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(SplitButtonDefaults.Spacing),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SplitButtonDefaults.LeadingButton(
                    onClick = actions.onPrimary,
                    enabled = actions.primaryEnabled,
                    shapes = SplitButtonDefaults.leadingButtonShapesFor(ActionHeight),
                    contentPadding = SplitButtonDefaults.leadingButtonContentPaddingFor(ActionHeight),
                    modifier = Modifier.weight(1f).height(ActionHeight),
                ) { PrimaryContent(actions.label) }
                DownloadSplitTrailingButton(
                    action = download,
                    enabled = actions.downloadEnabled,
                    onTap = actions.onDownloadTap,
                    size = ActionHeight,
                )
            }
        } else {
            Button(
                onClick = actions.onPrimary,
                enabled = actions.primaryEnabled,
                shapes = ButtonDefaults.shapes(),
                modifier = Modifier.weight(1f).heightIn(min = ActionHeight),
            ) { PrimaryContent(actions.label) }
        }
        FilledTonalIconButton(
            onClick = actions.onAddToShelf,
            shapes = IconButtonDefaults.shapes(),
            modifier = Modifier.size(ActionHeight).semantics { contentDescription = shelfName },
        ) { Icon(Icons.AutoMirrored.Outlined.PlaylistAdd, contentDescription = null) }
    }
}

/**
 * Tablet: an M3E standard button group, every segment labelled (separate buttons, 8 dp apart). Download and Add to
 * shelf take their own width so their labels never cut short (board: `flex: 0 0 auto`); Resume takes the rest of its
 * row and never less than half of it (spec §3.2.1). When a long label ("Delete download") leaves Resume less than
 * half, the two wrap onto a second row, as the board's row does (`flex-wrap: wrap`).
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun TabletActionGroup(
    actions: ListenActions,
    modifier: Modifier,
) {
    val shelfName = stringResource(Res.string.book_detail_add_to_shelf)
    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        val halfRow = maxWidth / 2
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            itemVerticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Button(
                onClick = actions.onPrimary,
                enabled = actions.primaryEnabled,
                shapes = ButtonDefaults.shapes(),
                modifier = Modifier.weight(1f).widthIn(min = halfRow).heightIn(min = ActionHeight),
            ) { PrimaryContent(actions.label) }
            actions.download?.let { download ->
                DownloadLabelledButton(
                    action = download,
                    enabled = actions.downloadEnabled,
                    onTap = actions.onDownloadTap,
                    minHeight = ActionHeight,
                )
            }
            FilledTonalButton(
                onClick = actions.onAddToShelf,
                shapes = ButtonDefaults.shapes(),
                modifier = Modifier.heightIn(min = ActionHeight),
            ) {
                Icon(Icons.AutoMirrored.Outlined.PlaylistAdd, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(shelfName, maxLines = 1)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun PrimaryContent(label: PrimaryLabel) {
    when (label) {
        // M3E's contained wait indicator, the audit's replacement for an indeterminate circular spinner.
        PrimaryLabel.Preparing -> {
            LoadingIndicator(Modifier.size(24.dp), color = LocalContentColor.current)
        }

        PrimaryLabel.UnavailableOffline -> {
            Icon(Icons.Outlined.CloudOff, contentDescription = null, modifier = Modifier.size(24.dp))
        }

        PrimaryLabel.Play, PrimaryLabel.Resume, PrimaryLabel.ListenAgain -> {
            Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(24.dp))
        }
    }
    Spacer(Modifier.width(8.dp))
    // The Button merges its children into one accessible node and the icon is decorative, so this Text is the
    // whole announced name: "Preparing…" replaces "Resume" so a screen reader never says Resume while it loads.
    Text(
        text =
            stringResource(
                when (label) {
                    PrimaryLabel.Play -> Res.string.book_detail_play
                    PrimaryLabel.Resume -> Res.string.book_detail_resume
                    PrimaryLabel.ListenAgain -> Res.string.book_detail_listen_again
                    PrimaryLabel.UnavailableOffline -> Res.string.book_detail_unavailable_offline
                    PrimaryLabel.Preparing -> Res.string.book_detail_preparing
                },
            ),
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
        maxLines = 1,
    )
}
