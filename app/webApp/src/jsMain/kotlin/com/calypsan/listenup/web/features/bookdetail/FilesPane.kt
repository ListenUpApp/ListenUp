package com.calypsan.listenup.web.features.bookdetail

import androidx.compose.runtime.Composable
import com.calypsan.listenup.client.domain.model.AudioFile
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
internal fun FilesPane(state: BookDetailUiState.Ready) {
    val files = state.book.audioFiles

    if (files.isEmpty()) {
        Panel(title = "Audio files") {
            PaneHint("This book has no audio files on record — the scanner hasn't indexed it yet.")
        }
        return
    }

    Div(attrs = { classes("bd-cols") }) {
        Div(attrs = { classes("bd-main") }) {
            Panel(
                title = "Audio files",
                flush = true,
                trailing = { MachineNote(formatBytes(files.sumOf { it.size })) },
            ) {
                DataTable(columns = FILE_COLUMNS, rows = files)
            }
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
        add(MetaEntry("Size", formatBytes(files.sumOf { it.size }), machine = true))
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
            Text(formatBytes(it.size))
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

/**
 * Binary megabytes, no decimals: these sit in a scannable column, and a self-hoster comparing
 * "178 MB" against their filesystem wants the number their file manager shows.
 */
private fun formatBytes(bytes: Long): String {
    if (bytes < BYTES_PER_MEGABYTE) return "${bytes / BYTES_PER_KILOBYTE} KB"
    if (bytes < BYTES_PER_GIGABYTE) return "${bytes / BYTES_PER_MEGABYTE} MB"
    // Integer tenths rather than floating point: one decimal is all a size column can use, and
    // this cannot render "1.0000000000000002 GB".
    val tenths = bytes * TENTHS / BYTES_PER_GIGABYTE
    return "${tenths / TENTHS}.${tenths % TENTHS} GB"
}

@Composable
private fun MachineNote(text: String) {
    Span(attrs = {
        classes("mono")
        style {
            property("font-size", "11.5px")
            property("color", "var(--ink-3)")
        }
    }) {
        Text(text)
    }
}

private const val MILLIS_PER_SECOND = 1000L

private const val BITS_PER_KILOBIT = 1000

private const val BYTES_PER_KILOBYTE = 1024L

private const val BYTES_PER_MEGABYTE = 1024L * 1024

private const val BYTES_PER_GIGABYTE = 1024L * 1024 * 1024

private const val TENTHS = 10L
