package com.calypsan.listenup.api.dto.scanner

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The Grouper's output: a set of files that belong to one logical book,
 * before any metadata inference has run.
 *
 *  - `rootRelPath` is the relative directory (or single file path, when
 *    `isFile = true`) that anchors the book.
 *  - `discFolders` lists the matched `CD1/`, `Disc 2/` subdirectories
 *    that were collapsed into this book — empty for non-multi-disc layouts.
 */
@Serializable
data class CandidateBook(
    @SerialName("rootRelPath")
    val rootRelPath: String,
    val isFile: Boolean,
    val files: List<FileEntry>,
    val discFolders: List<String> = emptyList(),
)
