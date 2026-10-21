package com.calypsan.listenup.web.features.bookdetail

import com.calypsan.listenup.client.presentation.bookdetail.byteSizeLabel
import androidx.compose.runtime.Composable
import com.calypsan.listenup.client.domain.model.AudioFile
import com.calypsan.listenup.client.domain.model.BookDocument
import com.calypsan.listenup.client.presentation.bookdetail.BookDetailUiState
import com.calypsan.listenup.client.presentation.bookdetail.audioFormatDisplay
import com.calypsan.listenup.web.design.ColumnAlign
import com.calypsan.listenup.web.design.DataTable
import com.calypsan.listenup.web.design.MetaEntry
import com.calypsan.listenup.web.design.MetaList
import com.calypsan.listenup.web.design.Panel
import com.calypsan.listenup.web.design.TableColumn
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Text

/**
 * What is actually on disk: one row per audio file, and the primary file's specs beside them.
 *
 * This is the pane a self-hoster opens the web client to check, and it is a render rather than a
 * new capability — every value comes off [AudioFile] and the shared `audioFormatDisplay`, which
 * the mobile Details section already reads.
 *
 * There is deliberately no filesystem path. [com.calypsan.listenup.client.domain.model.BookDetail]
 * carries a `folderId`, and a library folder's `rootPath` is the library root rather than this
 * book's directory — showing the root under a "Path" label would send someone to the wrong
 * directory. A per-book path is a contract change, not a web edit.
 */
@Composable
internal fun FilesPane(
    state: BookDetailUiState.Ready,
    documents: List<BookDocument>,
) {
    val files = state.book.audioFiles

    if (files.isEmpty()) {
        Panel(title = "Audio files") {
            PaneHint("This book has no audio files on record — the scanner hasn't indexed it yet.")
        }
        // ⛔ Not an early return any more. A book the scanner found a PDF for but no audio yet is
        // exactly when someone opens this tab to see what the server actually has.
        DocumentsPanel(state.book.id.value, documents)
        return
    }

    Div(attrs = { classes("bd-cols") }) {
        Div(attrs = { classes("bd-main") }) {
            Panel(
                title = "Audio files",
                flush = true,
                trailing = { MachineNote(byteSizeLabel(files.sumOf { it.size })) },
            ) {
                DataTable(columns = FILE_COLUMNS, rows = files, rowKey = { it.id })
            }
            DocumentsPanel(state.book.id.value, documents)
        }
        Div(attrs = { classes("bd-side") }) {
            Panel(title = "Audio") {
                MetaList(audioEntries(files))
            }
        }
    }
}

/**
 * The specs panel. Every row but the totals comes from the primary file, and each field is
 * dropped when the scanner couldn't determine it — a row reading "Unknown" is a row that
 * shouldn't be drawn.
 */
private fun audioEntries(files: List<AudioFile>): List<MetaEntry> {
    val display = audioFormatDisplay(files)
    return buildList {
        display.format?.let { add(MetaEntry("Format", it, machine = true)) }
        display.bitrate?.let { add(MetaEntry("Bitrate", it, machine = true)) }
        display.sampleRate?.let { add(MetaEntry("Sample rate", it, machine = true)) }
        display.channels?.let { add(MetaEntry("Channels", it, machine = true)) }
        add(MetaEntry("Size", byteSizeLabel(files.sumOf { it.size }), machine = true))
        add(MetaEntry("Files", files.size.toString(), machine = true))
    }
}

private val FILE_COLUMNS =
    listOf(
        TableColumn<AudioFile>("file", "File", mono = true) { Text(it.filename) },
        TableColumn("duration", "Duration", width = 96, align = ColumnAlign.End, mono = true) {
            Text(formatClock((it.duration / MILLIS_PER_SECOND).toInt()))
        },
        TableColumn("size", "Size", width = 88, align = ColumnAlign.End, mono = true) {
            Text(byteSizeLabel(it.size))
        },
        TableColumn("codec", "Codec", width = 108, align = ColumnAlign.End, mono = true) {
            Text(codecLabel(it))
        },
    )

/** "AAC 64k" — the codec and, when the file knows it, the bitrate that distinguishes two rips. */
private fun codecLabel(file: AudioFile): String {
    val codec = file.codec.takeIf { it.isNotBlank() }?.uppercase() ?: return "—"
    val kbps = file.bitrate?.let { it / BITS_PER_KILOBIT } ?: return codec
    return "$codec ${kbps}k"
}


@Composable
internal fun MachineNote(text: String) {
    Span(attrs = {
        classes("mono")
        style {
            property("font-size", "0.71875rem")
            property("color", "var(--ink-3)")
        }
    }) {
        Text(text)
    }
}

private const val MILLIS_PER_SECOND = 1000L

private const val BITS_PER_KILOBIT = 1000
