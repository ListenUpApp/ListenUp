package com.calypsan.listenup.client.data.local.documents

/**
 * Browser document cache: the same decision as `BrowserImageStorage`, for the same reason.
 *
 * Documents on web are served by the server's document endpoint and viewed with the browser's
 * own machinery (a PDF opens in a tab); caching the bytes again locally adds nothing the HTTP
 * cache doesn't already do. There is also no filesystem to cache into — kotlinx-io's js `Path`
 * requires Node's `path` module and cannot even be constructed in a browser, so the common
 * `DocumentStorageImpl` is structurally unusable here, not merely unwanted.
 *
 * Bookkeeping stays coherent per session: [write] records the path, [exists] answers from the
 * record, and a reload simply re-fetches — the cache is a derived store whose documented worst
 * case is exactly that.
 */
internal class BrowserDocumentStorage : DocumentStorage {
    private val written = mutableSetOf<String>()

    override fun pathFor(
        bookId: String,
        docId: String,
        format: String,
    ): String = "browser://documents/$bookId/$docId.$format"

    override fun exists(path: String): Boolean = path in written

    override suspend fun write(
        path: String,
        bytes: ByteArray,
    ) {
        written.add(path)
    }

    override suspend fun deleteCached(
        bookId: String,
        docId: String,
        format: String,
    ) {
        written.remove(pathFor(bookId, docId, format))
    }
}
