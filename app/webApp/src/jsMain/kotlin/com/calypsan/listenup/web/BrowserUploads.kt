package com.calypsan.listenup.web

import com.calypsan.listenup.client.domain.repository.UploadCandidate
import com.calypsan.listenup.client.presentation.admin.upload.UploadSelectionRefusal
import com.calypsan.listenup.web.features.bookdetail.formatBytes
import kotlinx.coroutines.CoroutineScope
import org.w3c.dom.HTMLInputElement
import org.w3c.files.File

/**
 * Everything a file picker handed over, as the [UploadCandidate]s the shared upload path speaks.
 *
 * Nothing is read here: each candidate's [BrowserFileSource] reads its file in slices as the upload
 * sends it, on [scope], so a selection of any size costs a few megabytes of tab memory and a file
 * that cannot be read fails its own transfer instead of vanishing from the selection. What one
 * upload may carry is decided by the shared `uploadSelectionRefusal`, not here.
 */
internal fun candidatesFrom(
    files: List<File>,
    scope: CoroutineScope,
): List<UploadCandidate> =
    files.map { file -> UploadCandidate(relPath = relPathOf(file), source = BrowserFileSource(file, scope)) }

/**
 * What a refused selection says, naming the limit it broke — the same sentences Android's dialogs
 * use. The shared `uploadSelectionRefusal` decides; this only words it.
 */
internal fun uploadRefusalSentence(refusal: UploadSelectionRefusal): String =
    when (refusal) {
        is UploadSelectionRefusal.TooManyFiles -> {
            "One upload can carry ${refusal.limit} files. That selection has ${refusal.count}, " +
                "so try it in smaller batches."
        }

        is UploadSelectionRefusal.TooLarge -> {
            "One upload can carry ${formatBytes(refusal.limitBytes)}. That selection is " +
                "${formatBytes(refusal.bytes)}, so try it in smaller batches."
        }

        is UploadSelectionRefusal.FileTooLarge -> {
            "\u201c${refusal.filename}\u201d is ${formatBytes(refusal.bytes)}, and a single file can be at most " +
                "${formatBytes(refusal.limitBytes)}."
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
