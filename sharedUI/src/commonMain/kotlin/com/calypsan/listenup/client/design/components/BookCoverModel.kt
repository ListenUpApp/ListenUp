package com.calypsan.listenup.client.design.components

/**
 * The cover-identity fields a book tile needs, bundled so they always travel together — a call-site
 * cannot silently omit [coverHash] (whose absence collapses the Coil cache key and serves a stale
 * cover). Per-surface state (progress, finished, playing) stays as separate `BookCard` params.
 */
data class BookCoverModel(
    val bookId: String,
    val title: String,
    val author: String?,
    val coverPath: String?,
    val coverHash: String?,
    val blurHash: String?,
)
