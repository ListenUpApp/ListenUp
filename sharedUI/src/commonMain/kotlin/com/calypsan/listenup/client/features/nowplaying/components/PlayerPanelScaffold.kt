package com.calypsan.listenup.client.features.nowplaying.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.window.core.layout.WindowSizeClass
import com.calypsan.listenup.client.design.theme.DisplayFontFamily
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.player_close
import org.jetbrains.compose.resources.stringResource

/**
 * Adaptive chrome for the Now Playing panels (speed / chapters / sleep). A Material bottom sheet on
 * compact and medium widths; a centred dialog at expanded width — the same breakpoint
 * [com.calypsan.listenup.client.features.contributors.FullCastSheet] uses, so the player's overlays
 * agree on when to switch.
 *
 * The scaffold intentionally does **not** wrap [content] in a scroll container: panels that need
 * scrolling (Chapters) supply their own bounded `LazyColumn`, and a `LazyColumn` nested in a
 * scrolling parent would be measured with infinite height and crash. Expanded-width (dialog) content
 * is therefore assumed to fit the viewport; only the Chapters panel scrolls.
 *
 * @param title Panel heading, rendered in the display font.
 * @param onDismiss Invoked when the sheet/dialog is dismissed (scrim tap, back, drag-down, close).
 * @param dialogWidth Max width of the expanded dialog (520.dp default; 560.dp for Chapters).
 * @param content Panel body, laid out in a [ColumnScope].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerPanelScaffold(
    title: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    dialogWidth: Dp = 520.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    val expanded =
        currentWindowAdaptiveInfo().windowSizeClass.isWidthAtLeastBreakpoint(
            WindowSizeClass.WIDTH_DP_EXPANDED_LOWER_BOUND,
        )
    if (expanded) {
        PlayerPanelDialog(
            title = title,
            onDismiss = onDismiss,
            dialogWidth = dialogWidth,
            modifier = modifier,
            content = content,
        )
    } else {
        PlayerPanelBottomSheet(title = title, onDismiss = onDismiss, modifier = modifier, content = content)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlayerPanelBottomSheet(
    title: String,
    onDismiss: () -> Unit,
    modifier: Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        modifier = modifier,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        dragHandle = {
            Surface(
                modifier = Modifier.padding(vertical = 12.dp).size(width = 36.dp, height = 5.dp),
                shape = RoundedCornerShape(3.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
            ) {}
        },
    ) {
        Column(
            modifier =
                Modifier.fillMaxWidth().navigationBarsPadding().padding(
                    start = 24.dp,
                    end = 24.dp,
                    bottom = 8.dp,
                ),
        ) {
            PanelTitle(title)
            content()
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun PlayerPanelDialog(
    title: String,
    onDismiss: () -> Unit,
    dialogWidth: Dp,
    modifier: Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            modifier = modifier.widthIn(max = dialogWidth).fillMaxWidth(),
        ) {
            Column(modifier = Modifier.padding(28.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 20.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    PanelTitle(title, modifier = Modifier.weight(1f))
                    Surface(
                        onClick = onDismiss,
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        modifier = Modifier.size(44.dp),
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = stringResource(Res.string.player_close),
                                tint = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }
                }
                content()
            }
        }
    }
}

@Composable
private fun PanelTitle(
    title: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text = title,
        modifier = modifier.padding(bottom = 4.dp),
        style =
            MaterialTheme.typography.headlineSmall.copy(
                fontFamily = DisplayFontFamily,
                fontWeight = FontWeight.Bold,
            ),
        color = MaterialTheme.colorScheme.onSurface,
    )
}
