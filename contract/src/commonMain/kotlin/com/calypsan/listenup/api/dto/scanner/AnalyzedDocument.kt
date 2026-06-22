package com.calypsan.listenup.api.dto.scanner

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * A supplementary ebook document collected from the book's folder during analysis.
 *
 * [relPath] is the book-root-relative path (e.g. `"map.pdf"` or `"extras/map.pdf"`).
 * The absolute path is used only transiently by [DocumentCollector] to compute
 * [size] and [hash]; it is never stored here.
 *
 * [format] is the file extension in lowercase (e.g. `"pdf"`, `"epub"`).
 * [hash] is the lowercase SHA-256 hex digest of the file's contents, used for
 * cache-busting and integrity checks when serving the document.
 */
@Serializable
@SerialName("AnalyzedDocument")
data class AnalyzedDocument(
    val relPath: String,
    val format: String,
    val size: Long,
    val hash: String,
)
