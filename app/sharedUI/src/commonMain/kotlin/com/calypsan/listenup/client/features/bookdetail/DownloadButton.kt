package com.calypsan.listenup.client.features.bookdetail

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.WifiOff
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.calypsan.listenup.client.design.components.ListenUpLoadingIndicatorSmall
import com.calypsan.listenup.client.design.haptics.LocalHaptics
import com.calypsan.listenup.client.features.bookdetail.components.DownloadAction
import com.calypsan.listenup.client.features.bookdetail.components.DownloadActionKind
import com.calypsan.listenup.client.features.bookdetail.components.DownloadTap
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.book_delete_download
import listenup.composeapp.generated.resources.book_detail_download
import listenup.composeapp.generated.resources.book_detail_download_size_a11y
import listenup.composeapp.generated.resources.book_detail_downloading_percent
import listenup.composeapp.generated.resources.book_detail_queued
import listenup.composeapp.generated.resources.book_detail_remove_download_short
import listenup.composeapp.generated.resources.book_detail_retry_download
import listenup.composeapp.generated.resources.book_detail_waiting_for_wifi
import org.jetbrains.compose.resources.stringResource

/**
 * Download as the trailing half of the phone's split pair (Simon, 2026-10-11): joined to Resume by M3E's split-button
 * shapes and seam, but its own action, so no chevron and no menu. A square of [size] whose accessible name carries
 * the file size (spec N2), never the book's length. Drawn from a [DownloadAction], so the phone and tablet renderings
 * share one truth. A downloaded book shows a trash bin named "Delete download"; tapping it asks to remove the
 * download (the screen confirms first).
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

/** "Download, 1.2 GB" while there is something to download; otherwise the state's own words. */
@Composable
private fun downloadAccessibleName(action: DownloadAction): String =
    if (action.kind == DownloadActionKind.Download) {
        stringResource(Res.string.book_detail_download_size_a11y, action.sizeLabel)
    } else {
        downloadLabel(action.kind)
    }

/**
 * The words beside the glyph on the labelled button: a downloaded book's "Remove", short enough to sit in the tablet's
 * one-row group beside the trash icon that already carries the meaning (Simon, 2026-10-19). The button's accessible
 * name stays the full "Delete download".
 */
@Composable
private fun shortDownloadLabel(kind: DownloadActionKind): String =
    if (kind ==
        DownloadActionKind.Downloaded
    ) {
        stringResource(Res.string.book_detail_remove_download_short)
    } else {
        downloadLabel(kind)
    }

@Composable
private fun downloadLabel(kind: DownloadActionKind): String =
    when (kind) {
        DownloadActionKind.Download -> stringResource(Res.string.book_detail_download)
        DownloadActionKind.Queued -> stringResource(Res.string.book_detail_queued)
        DownloadActionKind.WaitingForWifi -> stringResource(Res.string.book_detail_waiting_for_wifi)
        is DownloadActionKind.Downloading -> stringResource(Res.string.book_detail_downloading_percent, kind.percent)
        DownloadActionKind.Downloaded -> stringResource(Res.string.book_delete_download)
        DownloadActionKind.Retry -> stringResource(Res.string.book_detail_retry_download)
    }

@Composable
private fun DownloadGlyph(kind: DownloadActionKind) {
    when (kind) {
        DownloadActionKind.Download -> {
            Icon(Icons.Outlined.Download, contentDescription = null)
        }

        DownloadActionKind.Queued -> {
            ListenUpLoadingIndicatorSmall()
        }

        DownloadActionKind.WaitingForWifi -> {
            Icon(Icons.Outlined.WifiOff, contentDescription = null)
        }

        is DownloadActionKind.Downloading -> {
            CircularProgressIndicator(
                progress = { kind.percent / PERCENT },
                modifier = Modifier.size(24.dp),
                strokeWidth = 3.dp,
                color = LocalContentColor.current,
            )
        }

        DownloadActionKind.Downloaded -> {
            Icon(Icons.Outlined.Delete, contentDescription = null)
        }

        DownloadActionKind.Retry -> {
            Icon(Icons.Outlined.Refresh, contentDescription = null, tint = MaterialTheme.colorScheme.error)
        }
    }
}

private const val PERCENT = 100f
