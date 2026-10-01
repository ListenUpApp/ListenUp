package com.calypsan.listenup.client.features.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoStories
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Headphones
import androidx.compose.material.icons.outlined.TaskAlt
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.calypsan.listenup.api.dto.hardcover.HardcoverShareMode
import com.calypsan.listenup.client.design.components.SectionGroup
import com.calypsan.listenup.client.design.components.SectionSegment
import com.calypsan.listenup.client.design.components.SettingRow
import com.calypsan.listenup.client.design.haptics.LocalHaptics
import com.calypsan.listenup.client.design.theme.Spacing
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.hardcover_comes_back_line
import listenup.composeapp.generated.resources.hardcover_comes_back_never_listening
import listenup.composeapp.generated.resources.hardcover_share_mode_as_i_listen
import listenup.composeapp.generated.resources.hardcover_share_mode_finished_only
import listenup.composeapp.generated.resources.hardcover_share_mode_label
import listenup.composeapp.generated.resources.hardcover_shared_finished_row
import listenup.composeapp.generated.resources.hardcover_shared_finished_with_dates_row
import listenup.composeapp.generated.resources.hardcover_shared_nothing_while_listening
import listenup.composeapp.generated.resources.hardcover_shared_progress_row
import listenup.composeapp.generated.resources.hardcover_shared_started_row
import listenup.composeapp.generated.resources.hardcover_what_comes_back
import listenup.composeapp.generated.resources.hardcover_what_is_shared
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/** The material minimum touch target, kept as the segments' own height so the visible button is the target. */
private val ShareModeMinHeight = 48.dp

private val SHARE_MODES = HardcoverShareMode.entries

/**
 * What ListenUp sends to Hardcover, and what comes back. "Update Hardcover" chooses between As I listen
 * (books started, how far, books finished) and Only when I finish (finished books alone, with when they
 * were started and finished); the rows beneath say what the chosen mode sends. What comes back is the
 * same either way: reads from elsewhere, labelled in Readers and never counted as listening.
 */
@Composable
internal fun HardcoverWhatIsShared(
    shareMode: HardcoverShareMode,
    isSavingShareMode: Boolean,
    onSetShareMode: (HardcoverShareMode) -> Unit,
) {
    SectionGroup(label = stringResource(Res.string.hardcover_what_is_shared)) {
        SectionSegment {
            ShareModeChoice(shareMode = shareMode, isSaving = isSavingShareMode, onSetShareMode = onSetShareMode)
        }
        when (shareMode) {
            HardcoverShareMode.AS_I_LISTEN -> {
                SettingRow(
                    title = stringResource(Res.string.hardcover_shared_started_row),
                    icon = Icons.Outlined.AutoStories,
                )
                SettingRow(
                    title = stringResource(Res.string.hardcover_shared_progress_row),
                    icon = Icons.Outlined.Headphones,
                )
                SettingRow(
                    title = stringResource(Res.string.hardcover_shared_finished_row),
                    icon = Icons.Outlined.TaskAlt,
                )
            }

            HardcoverShareMode.FINISHED_ONLY -> {
                SettingRow(
                    title = stringResource(Res.string.hardcover_shared_finished_with_dates_row),
                    icon = Icons.Outlined.TaskAlt,
                )
                QuietLine(
                    icon = Icons.Outlined.Headphones,
                    text = stringResource(Res.string.hardcover_shared_nothing_while_listening),
                )
            }
        }
    }
    SectionGroup(label = stringResource(Res.string.hardcover_what_comes_back)) {
        val quiet = SpanStyle(color = MaterialTheme.colorScheme.onSurfaceVariant)
        val line = stringResource(Res.string.hardcover_comes_back_line)
        val never = stringResource(Res.string.hardcover_comes_back_never_listening)
        SectionSegment {
            Row(
                modifier = Modifier.padding(horizontal = 20.dp, vertical = Spacing.lg),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Icon(
                    imageVector = Icons.Outlined.Download,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text =
                        buildAnnotatedString {
                            append(line)
                            append(' ')
                            withStyle(quiet) { append(never) }
                        },
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
        }
    }
}

/**
 * "Update Hardcover" over Material 3's single-choice segmented button: a selectable group whose options
 * are radio buttons, so TalkBack announces each as one of two with its selected state. Disabled while a
 * choice saves, so a second tap can't race the first.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ShareModeChoice(
    shareMode: HardcoverShareMode,
    isSaving: Boolean,
    onSetShareMode: (HardcoverShareMode) -> Unit,
) {
    val haptics = LocalHaptics.current
    Column(
        modifier = Modifier.padding(horizontal = 20.dp, vertical = Spacing.lg),
        verticalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        Text(text = stringResource(Res.string.hardcover_share_mode_label), style = MaterialTheme.typography.titleMedium)
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            SHARE_MODES.forEachIndexed { index, mode ->
                SegmentedButton(
                    selected = mode == shareMode,
                    onClick = {
                        haptics.press()
                        onSetShareMode(mode)
                    },
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = SHARE_MODES.size),
                    enabled = !isSaving,
                    modifier = Modifier.heightIn(min = ShareModeMinHeight),
                ) {
                    Text(text = stringResource(mode.label))
                }
            }
        }
    }
}

/** Each mode's name. Deliberately no `else`: a new mode must fail to compile here rather than borrow a name. */
private val HardcoverShareMode.label: StringResource
    get() =
        when (this) {
            HardcoverShareMode.AS_I_LISTEN -> Res.string.hardcover_share_mode_as_i_listen
            HardcoverShareMode.FINISHED_ONLY -> Res.string.hardcover_share_mode_finished_only
        }
