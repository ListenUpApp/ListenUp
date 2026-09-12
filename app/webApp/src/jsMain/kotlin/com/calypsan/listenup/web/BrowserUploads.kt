package com.calypsan.listenup.web

import com.calypsan.listenup.client.domain.repository.UploadCandidate
import org.w3c.dom.HTMLInputElement
import org.w3c.files.File

/**
 * Everything a file picker handed over, as the [UploadCandidate]s the shared upload path speaks.
 *
 * ⛔ **Every file is read into memory before the upload starts, and that is a browser limit rather
 * than a choice.** `FileSource.openChannel()` is synchronous, a browser can only read a `File`
 * asynchronously, and the upload path needs a fresh channel per file — so there is nowhere to
 * stream from but a buffer this code already holds. The native clients stream from disk and have
 * no such ceiling; [UPLOAD_BYTE_CEILING] is where the browser is told to stop pretending, with a
 * sentence rather than an allocation crash halfway through a folder.
 *
 * Returns null when the selection is too large, so the caller can say so and leave the picker
 * untouched.
 */
internal suspend fun candidatesFrom(files: List<File>): List<UploadCandidate>? {
    if (files.sumOf { it.size.toDouble() } > UPLOAD_BYTE_CEILING) return null
    return files.mapNotNull { file ->
        val bytes = file.readByteArray() ?: return@mapNotNull null
        UploadCandidate(relPath = relPathOf(file), source = BrowserFileSource(file, bytes))
    }
}

/**
 * Where one picked file sits relative to the selection root.
 *
 * A folder pick (`webkitdirectory`) carries `webkitRelativePath` — `The Way of Kings/01.m4b` — and
 * that structure is exactly what the server groups on. Loose files carry an empty one, so they are
 * their own filename. ⛔ Never invent a folder for a loose file: the client transmits the structure
 * the user chose and guesses nothing about how many books it represents.
 */
internal fun relPathOf(file: File): String {
    val relative = file.asDynamic().webkitRelativePath as? String
    return if (relative.isNullOrBlank()) file.name else relative
}

/** Every file a picker collected, in the order the browser reported them. */
internal fun HTMLInputElement.pickedFiles(): List<File> {
    val list = files ?: return emptyList()
    return (0 until list.length).mapNotNull { list.item(it) }
}

/**
 * The most a browser tab is asked to hold at once — 2 GiB.
 *
 * Not a server limit and not a protocol one: it is roughly where a tab's own allocations start
 * failing, and failing *before* reading is the difference between a sentence and a dead page
 * halfway through someone's library.
 */
internal const val UPLOAD_BYTE_CEILING: Double = 2.0 * 1024 * 1024 * 1024
