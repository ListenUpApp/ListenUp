package com.calypsan.listenup.client.features.bookdetail.components

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.PlaylistAdd
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ButtonGroup
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.calypsan.listenup.client.design.haptics.LocalHaptics
import com.calypsan.listenup.client.domain.model.BookDownloadStatus
import com.calypsan.listenup.client.features.bookdetail.DownloadIconButton
import com.calypsan.listenup.client.features.bookdetail.DownloadLabelledButton
import com.calypsan.listenup.client.presentation.bookdetail.PrimaryAction
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.book_detail_add_to_shelf
import listenup.composeapp.generated.resources.book_detail_download_caption
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

/** Resume against the two labelled segments (1 each): enough that Resume holds at least half the row after the gaps. */
private const val RESUME_WEIGHT_LABELLED = 2.4f

/**
 * Book Detail's action row (spec A15, M3E addendum): an M3E **standard** button group, not a connected one, because
 * these are actions, not a choice. Resume is filled primary and takes the remaining width. Download and Add to shelf
 * are tonal: icon-only on a phone ([labelled] false) with their names in the accessible label, labelled on a tablet.
 * The caption under it says how big the download is (N3) until it is saved.
 */
@Suppress("LongParameterList", "LongMethod")
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
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
    val label = primaryLabelFor(primaryAction, isPreparing)
    val download = downloadActionFor(downloadStatus, isWaitingForWifi, downloadSizeBytes)
    val downloadEnabled = canDownload || download.tap != DownloadTap.Start
    val onDownloadTap: (DownloadTap) -> Unit = { tap ->
        when (tap) {
            DownloadTap.Start -> onDownload()
            DownloadTap.Cancel -> onCancelDownload()
            DownloadTap.Remove -> onRemoveDownload()
        }
    }
    val shelfName = stringResource(Res.string.book_detail_add_to_shelf)
    val resumeSource = remember { MutableInteractionSource() }
    val downloadSource = remember { MutableInteractionSource() }
    val shelfSource = remember { MutableInteractionSource() }

    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ButtonGroup(
            overflowIndicator = {},
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            customItem(
                buttonGroupContent = {
                    Button(
                        onClick = {
                            haptics.press()
                            onPrimaryClick()
                        },
                        enabled = isPrimaryEnabled(primaryAction),
                        shapes = ButtonDefaults.shapes(),
                        interactionSource = resumeSource,
                        modifier =
                            Modifier
                                .weight(if (labelled) RESUME_WEIGHT_LABELLED else 1f)
                                .animateWidth(resumeSource)
                                .heightIn(min = ActionHeight),
                    ) { PrimaryContent(label) }
                },
                menuContent = {},
            )
            if (supportsDownloads) {
                customItem(
                    buttonGroupContent = {
                        if (labelled) {
                            DownloadLabelledButton(
                                action = download,
                                enabled = downloadEnabled,
                                onTap = onDownloadTap,
                                minHeight = ActionHeight,
                                interactionSource = downloadSource,
                                modifier = Modifier.weight(1f).animateWidth(downloadSource),
                            )
                        } else {
                            DownloadIconButton(
                                action = download,
                                enabled = downloadEnabled,
                                onTap = onDownloadTap,
                                size = ActionHeight,
                                interactionSource = downloadSource,
                                modifier = Modifier.animateWidth(downloadSource),
                            )
                        }
                    },
                    menuContent = {},
                )
            }
            customItem(
                buttonGroupContent = {
                    if (labelled) {
                        FilledTonalButton(
                            onClick = onAddToShelf,
                            shapes = ButtonDefaults.shapes(),
                            interactionSource = shelfSource,
                            modifier = Modifier.weight(1f).animateWidth(shelfSource).heightIn(min = ActionHeight),
                        ) {
                            Icon(Icons.AutoMirrored.Outlined.PlaylistAdd, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text(shelfName, maxLines = 1)
                        }
                    } else {
                        FilledTonalIconButton(
                            onClick = onAddToShelf,
                            shapes = IconButtonDefaults.shapes(),
                            interactionSource = shelfSource,
                            modifier =
                                Modifier
                                    .animateWidth(shelfSource)
                                    .size(ActionHeight)
                                    .semantics { contentDescription = shelfName },
                        ) { Icon(Icons.AutoMirrored.Outlined.PlaylistAdd, contentDescription = null) }
                    }
                },
                menuContent = {},
            )
        }
        if (supportsDownloads && download.kind == DownloadActionKind.Download) {
            Text(
                text = stringResource(Res.string.book_detail_download_caption, download.sizeLabel),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
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
