package com.calypsan.listenup.client.automotive

import android.net.Uri

/**
 * The `content://` URI scheme for book cover art.
 *
 * Android Auto accepts only `content://` and `android.resource://` artwork URIs — `file://`
 * is rejected outright, including paths inside app-private storage. Every cover URI we hand
 * to a media controller is built here.
 *
 * The authority is derived from the running package rather than hardcoded, because
 * `:app:sharedUI` is a library module whose namespace differs from the app's `applicationId`.
 */
object CoverUri {
    /** Path segment for book covers, matching the manifest's `<provider>` declaration. */
    const val PATH_COVERS = "covers"

    private const val AUTHORITY_SUFFIX = ".covers"

    /**
     * Book IDs the app issues are alphanumerics plus `-` and `_`. Anything else — a dot, a
     * path separator, an empty string — cannot name a cover and is refused before it reaches
     * the filesystem. This is the traversal guard: `Uri.parse` does not normalise `..`.
     */
    private val SAFE_BOOK_ID = Regex("^[A-Za-z0-9_-]+$")

    /** The provider authority for [packageName], e.g. `com.example.app.covers`. */
    fun authority(packageName: String): String = packageName + AUTHORITY_SUFFIX

    /** The cover URI for [bookId], e.g. `content://com.example.app.covers/covers/bk-123`. */
    fun forBook(
        packageName: String,
        bookId: String,
    ): Uri =
        Uri
            .Builder()
            .scheme("content")
            .authority(authority(packageName))
            .appendPath(PATH_COVERS)
            .appendPath(bookId)
            .build()

    /**
     * The prefix URI covering every book cover — granted once per controller connection with
     * `FLAG_GRANT_PREFIX_URI_PERMISSION` so access is O(1) rather than one grant per book.
     */
    fun prefixUri(packageName: String): Uri =
        Uri
            .Builder()
            .scheme("content")
            .authority(authority(packageName))
            .appendPath(PATH_COVERS)
            .build()

    /** True when [bookId] is safe to resolve against the covers directory. */
    fun isSafeBookId(bookId: String): Boolean = SAFE_BOOK_ID.matches(bookId)

    /**
     * The book ID named by [uri], or null when [uri] is not a well-formed, safe cover URI.
     * Rejects wrong path segments, missing or extra segments, and unsafe IDs.
     */
    fun bookIdFrom(uri: Uri): String? {
        val segments = uri.pathSegments
        if (segments.size != 2) return null
        if (segments[0] != PATH_COVERS) return null
        val bookId = segments[1]
        return if (isSafeBookId(bookId)) bookId else null
    }
}
