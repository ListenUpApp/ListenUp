package com.calypsan.listenup.client.util

import android.content.ContentResolver
import android.database.Cursor
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import com.calypsan.listenup.client.domain.repository.UploadCandidate
import com.calypsan.listenup.core.AndroidFileSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import io.github.oshai.kotlinlogging.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * Turns a SAF selection into the [UploadCandidate]s the upload repository streams.
 *
 * Two shapes, because the two ways people actually have audiobooks on disk are different:
 *
 * - **Files** — a flat multi-select. Each candidate's `relPath` is its bare filename, so the server
 *   sees one directory of files and its grouper decides how many books that is.
 * - **Folder** — a document tree, walked recursively. Each candidate's `relPath` keeps its position
 *   under the picked folder, **including the picked folder's own name**. That last part matters: a
 *   folder called `Rediscovering Christmas` is the strongest title signal the scanner will get, and
 *   dropping it would throw that away before the server ever saw it.
 *
 * The walk uses [DocumentsContract] directly rather than `androidx.documentfile`. `DocumentFile`
 * would be tidier to read, but it costs a dependency to do what one cursor loop does, and it
 * allocates an object per node on trees that can run to hundreds of files.
 */
private val PROJECTION =
    arrayOf(
        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
        DocumentsContract.Document.COLUMN_MIME_TYPE,
        DocumentsContract.Document.COLUMN_SIZE,
    )

/** Remembers a launcher for picking loose files to upload. */
@Composable
fun rememberUploadFilePicker(onPicked: (List<UploadCandidate>) -> Unit): () -> Unit {
    val context = LocalContext.current
    val launcher =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            val resolver = context.contentResolver
            onPicked(uris.mapNotNull { uri -> resolver.candidateForFlatFile(uri) })
        }
    return { launcher.launch(arrayOf("*/*")) }
}

/** Remembers a launcher for picking a whole folder to upload, subdirectories included. */
@Composable
fun rememberUploadFolderPicker(onPicked: (List<UploadCandidate>) -> Unit): () -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val launcher =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { treeUri ->
            if (treeUri == null) {
                onPicked(emptyList())
                return@rememberLauncherForActivityResult
            }
            // The walk costs one binder round-trip to the DocumentsProvider PER DIRECTORY. A real
            // audiobook folder is hundreds of directories, and a cloud-backed or SD-card provider
            // can take 10-100ms each — comfortably an ANR if this ran where the result callback
            // lands, which is the main thread.
            scope.launch {
                val candidates = withContext(Dispatchers.IO) { context.contentResolver.candidatesUnderTree(treeUri) }
                onPicked(candidates)
            }
        }
    return { launcher.launch(null) }
}

/** One flat-picked file: `relPath` is the display name, so it lands at the session root. */
private fun ContentResolver.candidateForFlatFile(uri: Uri): UploadCandidate? {
    val (name, size) = openableMetadata(uri) ?: return null
    return UploadCandidate(
        relPath = name,
        source = AndroidFileSource(contentResolver = this, uri = uri, filename = name, size = size),
    )
}

/** Display name and size for a SAF document, or null when the provider will not say. */
private fun ContentResolver.openableMetadata(uri: Uri): Pair<String, Long?>? =
    try {
        query(uri, null, null, null, null)?.use { cursor ->
            if (!cursor.moveToFirst()) return null
            val name = cursor.stringOrNull(OpenableColumns.DISPLAY_NAME) ?: return null
            val size = cursor.longOrNull(OpenableColumns.SIZE)
            name to size
        }
    } catch (e: SecurityException) {
        logger.warn(e) { "no permission to read picked document $uri" }
        null
    }

/**
 * Every file under [treeUri], depth-first, with paths relative to the picked folder's parent — so
 * the folder the user chose is itself the first path segment.
 */
private fun ContentResolver.candidatesUnderTree(treeUri: Uri): List<UploadCandidate> {
    val rootId = DocumentsContract.getTreeDocumentId(treeUri)
    val rootName =
        openableMetadata(DocumentsContract.buildDocumentUriUsingTree(treeUri, rootId))?.first
            ?: return emptyList()

    val candidates = mutableListOf<UploadCandidate>()
    // Iterative, not recursive: a directory's cursor is closed before its children are visited,
    // so the walk holds one CursorWindow at a time instead of one per level of depth.
    val queue = ArrayDeque(listOf(rootId to rootName))
    // A DocumentsProvider is free to expose a cyclic tree (symlinked storage, third-party
    // providers). Recursion had no defence and would run until StackOverflow; the file cap in the
    // screen is checked only after the walk returns, so it bounded nothing here.
    val visited = mutableSetOf(rootId)

    while (queue.isNotEmpty()) {
        val (documentId, prefix) = queue.removeFirst()
        for (child in childrenOf(treeUri, documentId)) {
            val relPath = "$prefix/${child.name}"
            if (child.isDirectory) {
                if (visited.add(child.documentId)) queue.addLast(child.documentId to relPath)
            } else {
                candidates +=
                    UploadCandidate(
                        relPath = relPath,
                        source =
                            AndroidFileSource(
                                contentResolver = this,
                                uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, child.documentId),
                                filename = child.name,
                                size = child.size,
                            ),
                    )
            }
        }
    }
    return candidates
}

/** One entry under a tree directory. */
private data class TreeChild(
    val documentId: String,
    val name: String,
    val isDirectory: Boolean,
    val size: Long?,
)

/** Reads one directory's children and closes its cursor before returning. */
private fun ContentResolver.childrenOf(
    treeUri: Uri,
    documentId: String,
): List<TreeChild> {
    val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, documentId)
    val cursor =
        try {
            query(childrenUri, PROJECTION, null, null, null)
        } catch (e: SecurityException) {
            logger.warn(e) { "no permission to list $documentId under $treeUri" }
            null
        } ?: return emptyList()

    return cursor.use {
        buildList {
            while (it.moveToNext()) {
                val childId = it.getString(0) ?: continue
                val name = it.getString(1) ?: continue
                add(
                    TreeChild(
                        documentId = childId,
                        name = name,
                        isDirectory = it.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR,
                        size = if (it.isNull(3)) null else it.getLong(3),
                    ),
                )
            }
        }
    }
}

private fun Cursor.stringOrNull(column: String): String? =
    getColumnIndex(column).takeIf { it >= 0 }?.let { getString(it) }

private fun Cursor.longOrNull(column: String): Long? =
    getColumnIndex(column)
        .takeIf { it >= 0 && !isNull(it) }
        ?.let { getLong(it) }
        ?.takeIf { it > 0 }
