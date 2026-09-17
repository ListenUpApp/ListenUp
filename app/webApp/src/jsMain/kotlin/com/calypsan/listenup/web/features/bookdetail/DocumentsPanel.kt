package com.calypsan.listenup.web.features.bookdetail

import androidx.compose.runtime.Composable
import com.calypsan.listenup.client.domain.model.BookDocument
import com.calypsan.listenup.web.design.ColumnAlign
import com.calypsan.listenup.web.design.DataTable
import com.calypsan.listenup.web.design.Icon
import com.calypsan.listenup.web.design.Panel
import com.calypsan.listenup.web.design.TableColumn
import com.calypsan.listenup.web.design.WebIcon
import org.jetbrains.compose.web.attributes.ATarget
import org.jetbrains.compose.web.attributes.target
import org.jetbrains.compose.web.dom.A
import org.jetbrains.compose.web.dom.Text

/**
 * The supplementary documents shipped beside the audio: a PDF map, a bonus chapter, liner notes.
 *
 * ⛔ Web showed none of these. `BookDetailViewModel.documents` has always been there, Android
 * renders it in the detail body and iOS has a whole DocumentReader — so a self-hoster who dropped
 * `map.pdf` next to their audiobook could see it on their phone and not in the browser.
 *
 * **Why there is no viewer here.** The natives need one because they must first download the bytes
 * to a local path and then render them themselves (`PdfRenderer`, `PDFKit`). A browser already has
 * a better PDF viewer than anything we would port, and the server was built for it: the document
 * route is mounted inside `BLOB_READ_PROVIDER`, whose KDoc names "an `<img src>` or a document
 * link" as the reason it accepts a cookie. So a row is a link, the browser renders it, and
 * `BookDetailViewModel.onOpenDocument` — whose whole job is resolving a local path for a platform
 * viewer — is deliberately left unwired. `BrowserDocumentStorage` committed to this shape already.
 */
@Composable
internal fun DocumentsPanel(
    bookId: String,
    documents: List<BookDocument>,
) {
    if (documents.isEmpty()) return

    Panel(
        title = "Documents",
        flush = true,
        trailing = { MachineNote(formatBytes(documents.sumOf { it.size })) },
    ) {
        DataTable(columns = documentColumns(bookId), rows = documents)
    }
}

/**
 * Columns are built per book because the link needs the book id, and a document only knows its own.
 */
private fun documentColumns(bookId: String): List<TableColumn<BookDocument>> =
    listOf(
        TableColumn("name", "Document", mono = true) { doc ->
            Icon(WebIcon.FileText, size = DOC_ICON)
            Text(" ${documentBasename(doc.filename)}")
        },
        TableColumn("format", "Format", width = 88, align = ColumnAlign.End, mono = true) { doc ->
            Text(doc.format.uppercase())
        },
        TableColumn("size", "Size", width = 88, align = ColumnAlign.End, mono = true) { doc ->
            Text(formatBytes(doc.size))
        },
        TableColumn("open", "", width = 104, align = ColumnAlign.End) { doc ->
            DocumentLink(bookId, doc)
        },
    )

/**
 * A plain anchor, not a button calling `window.open`.
 *
 * ⛔ That is the point: an anchor is what a reader can middle-click, long-press, copy, or open in a
 * background tab, and the browser attaches the access cookie to it for free. Routing this through
 * JavaScript would take all of that away and gain nothing.
 *
 * A PDF opens in a new tab because the browser renders it there. Anything else — epub, cbz, a
 * format nobody has taught a browser — gets `download`, because a tab showing raw bytes helps no
 * one. Web is better off than the natives here: they answer a non-PDF with "viewer coming soon"
 * ([com.calypsan.listenup.client.presentation.bookdetail.BookDetailNavAction.ShowViewerComingSoon]),
 * where a browser can simply hand over the file.
 */
@Composable
private fun DocumentLink(
    bookId: String,
    doc: BookDocument,
) {
    val viewable = doc.format.lowercase() == PDF
    val name = documentBasename(doc.filename)
    A(href = documentUrl(bookId, doc.id), attrs = {
        classes("btn-o", "doc-open")
        if (viewable) {
            target(ATarget.Blank)
            attr("rel", "noopener")
            attr("aria-label", "Open $name in a new tab")
        } else {
            attr("download", name)
            attr("aria-label", "Download $name")
        }
    }) {
        Icon(if (viewable) WebIcon.Eye else WebIcon.Download, size = DOC_ICON)
        Text(if (viewable) " Open" else " Download")
    }
}

/**
 * The document's bytes on the server.
 *
 * Relative for the same reason `coverUrl` is: the server serves this bundle, so a relative URL is
 * same-origin by construction — which is also what makes the `SameSite=Strict` access cookie ride
 * along. No cache-busting query: a document is addressed by its own UUID, and the server rotates
 * that id when a rescan changes the file.
 */
internal fun documentUrl(
    bookId: String,
    docId: String,
): String = "/api/v1/books/$bookId/documents/$docId"

/**
 * `"extras/map.pdf"` → `"map.pdf"`.
 *
 * [BookDocument.filename] is book-root-relative and may carry directories; every client displays
 * the basename. Kept here beside its only caller rather than in a shared util, which is where
 * iOS keeps its own copy too.
 */
internal fun documentBasename(filename: String): String = filename.substringAfterLast('/')

private const val PDF = "pdf"

private const val DOC_ICON = 14
