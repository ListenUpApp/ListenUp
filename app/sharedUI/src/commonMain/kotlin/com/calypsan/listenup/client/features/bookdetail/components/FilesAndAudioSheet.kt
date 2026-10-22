package com.calypsan.listenup.client.features.bookdetail.components

import com.calypsan.listenup.client.presentation.bookdetail.byteSizeLabel
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import com.calypsan.listenup.client.core.DurationFormatter
import com.calypsan.listenup.client.design.theme.Spacing
import com.calypsan.listenup.client.domain.model.AudioFile
import com.calypsan.listenup.client.presentation.bookdetail.audioFormatDisplay
import kotlin.time.Duration.Companion.milliseconds
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.book_detail_bitrate
import listenup.composeapp.generated.resources.book_detail_channels
import listenup.composeapp.generated.resources.book_detail_files_sheet_title
import listenup.composeapp.generated.resources.book_detail_format
import listenup.composeapp.generated.resources.book_detail_sample_rate
import org.jetbrains.compose.resources.stringResource

/**
 * Files & audio (spec §3.2.1 D32): the sheet behind the Details row and the scan warning's Review. Each file (its name,
 * length and size), then the format rows that left Details: Format, Bitrate, Sample rate, Channels.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FilesAndAudioSheet(
    audioFiles: List<AudioFile>,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        FilesAndAudioContent(
            audioFiles = audioFiles,
            modifier =
                Modifier
                    .verticalScroll(rememberScrollState())
                    .navigationBarsPadding()
                    .padding(horizontal = Spacing.screenMargin)
                    .padding(bottom = Spacing.lg),
        )
    }
}

/** The Files & audio sheet's body: the files, a divider, then the four format rows. */
@Composable
internal fun FilesAndAudioContent(
    audioFiles: List<AudioFile>,
    modifier: Modifier = Modifier,
) {
    val format = audioFormatDisplay(audioFiles)
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        Text(
            text = stringResource(Res.string.book_detail_files_sheet_title),
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.semantics { heading() },
        )
        audioFiles.forEach { file ->
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
                Text(
                    text = file.filename.substringAfterLast('/'),
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = DurationFormatter.hoursMinutesAlways(file.duration.milliseconds),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = byteSizeLabel(file.size),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        listOfNotNull(
            format.format?.let { stringResource(Res.string.book_detail_format) to it },
            format.bitrate?.let { stringResource(Res.string.book_detail_bitrate) to it },
            format.sampleRate?.let { stringResource(Res.string.book_detail_sample_rate) to it },
            format.channels?.let { stringResource(Res.string.book_detail_channels) to it },
        ).forEach { (label, value) ->
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
                Text(text = value, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
