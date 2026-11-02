package com.calypsan.listenup.client.design.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfoV2
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.window.core.layout.WindowSizeClass
import com.calypsan.listenup.client.design.MinTouchTarget
import com.calypsan.listenup.client.design.theme.DisplayFontFamily
import com.calypsan.listenup.client.design.theme.Spacing
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.player_close
import org.jetbrains.compose.resources.stringResource

/** Which chrome [AdaptiveSheet] is wearing, for a body whose layout differs between the two. */
enum class SheetPresentation {
    /** Compact and medium widths. */
    BottomSheet,

    /** Expanded width. */
    Dialog,
}

/**
 * The app's one "bottom sheet on a phone, dialog on a wide screen" chrome: the Now Playing panels (speed, sleep, boost,
 * chapters; spec §3.8) and the full cast. A Material bottom sheet on compact and medium widths, on
 * `surfaceContainerLow` with 28dp top corners and a drag handle (m3.material.io/components/bottom-sheets/guidelines); a
 * centred dialog at expanded width. One breakpoint, so every overlay agrees on when to switch.
 *
 * The body scrolls when it is taller than the sheet or the dialog, which at the largest font the speed and sleep
 * sheets are (M3 bottom sheets scroll their content; §8). A panel that brings its own bounded `LazyColumn` (Chapters)
 * passes `scrollable = false`, because a lazy list inside a scrolling parent is measured with infinite height. The
 * title stays put above the scrolling body.
 *
 * The title is the display face at Medium weight (F6; m3.material.io/styles/typography/type-scale-tokens), and the
 * dialog's close is a 48dp tonal icon button (§8).
 *
 * @param title Panel heading, rendered in the display font.
 * @param subtitle An optional supporting line under the title (the full cast's "14 narrators").
 * @param onDismiss Invoked when the sheet/dialog is dismissed (scrim tap, back, drag-down, close).
 * @param dialogWidth Max width of the expanded dialog (520.dp default; 560.dp for Chapters).
 * @param scrollable Whether the scaffold scrolls [content]; false only for content with its own lazy list.
 * @param content Panel body, laid out in a [ColumnScope]; told the [SheetPresentation] it is in, for a body that lays
 *   out differently as a dialog.
 */
@Composable
fun AdaptiveSheet(
    title: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    dialogWidth: Dp = 520.dp,
    scrollable: Boolean = true,
    content: @Composable ColumnScope.(SheetPresentation) -> Unit,
) {
    val expanded =
        currentWindowAdaptiveInfoV2().windowSizeClass.isWidthAtLeastBreakpoint(
            WindowSizeClass.WIDTH_DP_EXPANDED_LOWER_BOUND,
        )
    if (expanded) {
        AdaptiveDialog(
            title = title,
            subtitle = subtitle,
            onDismiss = onDismiss,
            dialogWidth = dialogWidth,
            scrollable = scrollable,
            modifier = modifier,
            content = content,
        )
    } else {
        AdaptiveBottomSheet(
            title = title,
            subtitle = subtitle,
            onDismiss = onDismiss,
            scrollable = scrollable,
            modifier = modifier,
            content = content,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AdaptiveBottomSheet(
    title: String,
    subtitle: String?,
    onDismiss: () -> Unit,
    scrollable: Boolean,
    modifier: Modifier,
    content: @Composable ColumnScope.(SheetPresentation) -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        modifier = modifier,
        sheetState =
            rememberBottomSheetState(
                initialValue = SheetValue.Hidden,
                enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded),
            ),
        shape = BottomSheetDefaults.ExpandedShape,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(
            modifier =
                Modifier.fillMaxWidth().navigationBarsPadding().padding(
                    start = Spacing.screenMargin,
                    end = Spacing.screenMargin,
                    bottom = 8.dp,
                ),
        ) {
            PanelTitle(title, subtitle)
            PanelBody(scrollable = scrollable) {
                content(SheetPresentation.BottomSheet)
                Spacer(Modifier.height(16.dp))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun AdaptiveDialog(
    title: String,
    subtitle: String?,
    onDismiss: () -> Unit,
    dialogWidth: Dp,
    scrollable: Boolean,
    modifier: Modifier,
    content: @Composable ColumnScope.(SheetPresentation) -> Unit,
) {
    val windowHeight =
        with(LocalDensity.current) {
            LocalWindowInfo.current.containerSize.height
                .toDp()
        }
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            modifier =
                modifier
                    .widthIn(max = dialogWidth)
                    .fillMaxWidth()
                    .heightIn(max = (windowHeight - DIALOG_VERTICAL_ROOM).coerceAtLeast(MinTouchTarget)),
        ) {
            Column(modifier = Modifier.padding(28.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 20.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    PanelTitle(title, subtitle, modifier = Modifier.weight(1f))
                    FilledTonalIconButton(
                        onClick = onDismiss,
                        shapes = IconButtonDefaults.shapes(),
                        colors =
                            IconButtonDefaults.filledTonalIconButtonColors(
                                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                                contentColor = MaterialTheme.colorScheme.onSurface,
                            ),
                        modifier = Modifier.size(MinTouchTarget),
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = stringResource(Res.string.player_close),
                        )
                    }
                }
                PanelBody(scrollable = scrollable) { content(SheetPresentation.Dialog) }
            }
        }
    }
}

/** The body under the title: scrolling unless the panel brings its own lazy list. */
@Composable
private fun ColumnScope.PanelBody(
    scrollable: Boolean,
    content: @Composable ColumnScope.() -> Unit,
) {
    val scroll = if (scrollable) Modifier.verticalScroll(rememberScrollState()) else Modifier
    Column(modifier = Modifier.weight(1f, fill = false).then(scroll), content = content)
}

@Composable
private fun PanelTitle(
    title: String,
    subtitle: String?,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.padding(bottom = 4.dp)) {
        Text(
            text = title,
            style =
                MaterialTheme.typography.headlineSmall.copy(
                    fontFamily = DisplayFontFamily,
                    fontWeight = FontWeight.Medium,
                ),
            color = MaterialTheme.colorScheme.onSurface,
        )
        subtitle?.let { supportingLine ->
            Text(
                text = supportingLine,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** What the dialog leaves free above and below it, so a tall panel scrolls inside the dialog, not off the window. */
private val DIALOG_VERTICAL_ROOM = 96.dp
