package com.calypsan.listenup.client.features.bookdetail

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SplitButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.calypsan.listenup.client.design.haptics.LocalHaptics
import com.calypsan.listenup.client.design.icons.ListenUpIcons
import com.calypsan.listenup.client.features.bookdetail.components.DownloadAction
import com.calypsan.listenup.client.features.bookdetail.components.DownloadActionKind
import com.calypsan.listenup.client.features.bookdetail.components.DownloadTap
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.book_delete_download
import listenup.composeapp.generated.resources.book_detail_download
import listenup.composeapp.generated.resources.book_detail_download_size_a11y
import listenup.composeapp.generated.resources.book_detail_remove_download_short
import listenup.composeapp.generated.resources.book_detail_retry_download
import listenup.composeapp.generated.resources.downloads_a11y_cancel_queued
import listenup.composeapp.generated.resources.downloads_a11y_cancel_waiting
import listenup.composeapp.generated.resources.downloads_a11y_cancel_wifi
import listenup.composeapp.generated.resources.downloads_a11y_pause
import listenup.composeapp.generated.resources.downloads_a11y_resume
import listenup.composeapp.generated.resources.downloads_label_pause
import listenup.composeapp.generated.resources.book_download_row_queued
import listenup.composeapp.generated.resources.downloads_label_resume
import listenup.composeapp.generated.resources.downloads_label_retry
import listenup.composeapp.generated.resources.downloads_label_waiting
import listenup.composeapp.generated.resources.book_download_row_waiting_for_wifi
import org.jetbrains.compose.resources.stringResource

/**
 * Download as the trailing half of the phone's split pair (Simon, 2026-10-11): joined to Resume by M3E's split-button
 * shapes and seam, but its own action, so no chevron and no menu. A square of [size] whose accessible name carries
 * the file size (spec N2), never the book's length. Drawn from a [DownloadAction], so the phone and tablet renderings
 * share one truth: a moving download's ring pauses it, a paused one's ring resumes it, and a downloaded book shows a
 * trash bin named "Delete download" (the screen confirms first).
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun DownloadSplitTrailingButton(
    action: DownloadAction,
    enabled: Boolean,
    onTap: (DownloadTap) -> Unit,
    size: Dp,
    modifier: Modifier = Modifier,
) {
    val haptics = LocalHaptics.current
    val name = downloadAccessibleName(action)
    SplitButtonDefaults.TrailingButton(
        onClick = {
            if (action.tap == DownloadTap.Start) haptics.commit()
            onTap(action.tap)
        },
        enabled = enabled,
        shapes = SplitButtonDefaults.trailingButtonShapesFor(size),
        colors = ButtonDefaults.filledTonalButtonColors(),
        elevation = null,
        contentPadding = PaddingValues(0.dp),
        modifier = modifier.size(size).semantics { contentDescription = name },
    ) { DownloadGlyph(action.kind) }
}

/** Labelled Download control (tablet): a tonal button of at least [minHeight] with the same states, worded. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun DownloadLabelledButton(
    action: DownloadAction,
    enabled: Boolean,
    onTap: (DownloadTap) -> Unit,
    minHeight: Dp,
    modifier: Modifier = Modifier,
    interactionSource: MutableInteractionSource = remember { MutableInteractionSource() },
) {
    val haptics = LocalHaptics.current
    val name = downloadAccessibleName(action)
    FilledTonalButton(
        onClick = {
            if (action.tap == DownloadTap.Start) haptics.commit()
            onTap(action.tap)
        },
        enabled = enabled,
        shapes = ButtonDefaults.shapes(),
        interactionSource = interactionSource,
        modifier = modifier.heightIn(min = minHeight).semantics { contentDescription = name },
    ) {
        DownloadGlyph(action.kind)
        Spacer(Modifier.width(8.dp))
        Text(shortDownloadLabel(action.kind), maxLines = 1)
    }
}

/**
 * The control's accessible name: "Download, 1.2 GB" while there is something to download, otherwise what a tap does
 * and where it stands ("Pause download, 33%", "Cancel download, queued", "Resume download, 33%").
 */
@Composable
private fun downloadAccessibleName(action: DownloadAction): String =
    when (val kind = action.kind) {
        DownloadActionKind.Download -> stringResource(Res.string.book_detail_download_size_a11y, action.sizeLabel)
        is DownloadActionKind.Downloading -> stringResource(Res.string.downloads_a11y_pause, kind.percent)
        DownloadActionKind.Queued -> stringResource(Res.string.downloads_a11y_cancel_queued)
        DownloadActionKind.WaitingForWifi -> stringResource(Res.string.downloads_a11y_cancel_wifi)
        DownloadActionKind.WaitingToResume -> stringResource(Res.string.downloads_a11y_cancel_waiting)
        is DownloadActionKind.PausedByYou -> stringResource(Res.string.downloads_a11y_resume, kind.percent)
        DownloadActionKind.Downloaded -> stringResource(Res.string.book_delete_download)
        DownloadActionKind.Retry -> stringResource(Res.string.book_detail_retry_download)
    }

/**
 * The words beside the glyph on the labelled (tablet) button, the same states worded: Download · Pause · Queued ·
 * Waiting for Wi-Fi · Waiting · Resume · Retry · Remove. A downloaded book's "Remove" stays short enough for the
 * one-row group beside the trash icon that already carries the meaning (Simon, 2026-10-19).
 */
@Composable
private fun shortDownloadLabel(kind: DownloadActionKind): String =
    when (kind) {
        DownloadActionKind.Download -> stringResource(Res.string.book_detail_download)
        is DownloadActionKind.Downloading -> stringResource(Res.string.downloads_label_pause)
        DownloadActionKind.Queued -> stringResource(Res.string.book_download_row_queued)
        DownloadActionKind.WaitingForWifi -> stringResource(Res.string.book_download_row_waiting_for_wifi)
        DownloadActionKind.WaitingToResume -> stringResource(Res.string.downloads_label_waiting)
        is DownloadActionKind.PausedByYou -> stringResource(Res.string.downloads_label_resume)
        DownloadActionKind.Downloaded -> stringResource(Res.string.book_detail_remove_download_short)
        DownloadActionKind.Retry -> stringResource(Res.string.downloads_label_retry)
    }

@Composable
private fun DownloadGlyph(kind: DownloadActionKind) {
    when (kind) {
        DownloadActionKind.Download -> {
            Icon(ListenUpIcons.Download, contentDescription = null)
        }

        DownloadActionKind.Queued -> {
            Icon(ListenUpIcons.Schedule, contentDescription = null)
        }

        DownloadActionKind.WaitingForWifi -> {
            Icon(ListenUpIcons.WifiOff, contentDescription = null)
        }

        DownloadActionKind.WaitingToResume -> {
            Icon(ListenUpIcons.History, contentDescription = null)
        }

        is DownloadActionKind.Downloading -> {
            ProgressRingGlyph(percent = kind.percent, moving = true, center = ListenUpIcons.PauseFilled)
        }

        is DownloadActionKind.PausedByYou -> {
            ProgressRingGlyph(percent = kind.percent, moving = false, center = ListenUpIcons.ArrowDownwardFilled)
        }

        DownloadActionKind.Downloaded -> {
            Icon(ListenUpIcons.Delete, contentDescription = null)
        }

        DownloadActionKind.Retry -> {
            Icon(ListenUpIcons.Refresh, contentDescription = null, tint = MaterialTheme.colorScheme.error)
        }
    }
}

/**
 * A determinate ring with the tap's own glyph inside: pause bars while bytes move, a down arrow once paused. A held
 * download keeps its place in a quieter colour, so moving and held never read alike.
 */
@Composable
private fun ProgressRingGlyph(
    percent: Int,
    moving: Boolean,
    center: ImageVector,
) {
    val content = LocalContentColor.current
    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(26.dp)) {
        CircularProgressIndicator(
            progress = { percent / PERCENT },
            modifier = Modifier.size(26.dp),
            strokeWidth = 3.dp,
            color = if (moving) content else MaterialTheme.colorScheme.outline,
            trackColor = content.copy(alpha = RING_TRACK_ALPHA),
        )
        Icon(center, contentDescription = null, modifier = Modifier.size(14.dp))
    }
}

private const val PERCENT = 100f
private const val RING_TRACK_ALPHA = 0.18f
