package com.calypsan.listenup.client.presentation.admin.upload

import com.calypsan.listenup.api.dto.uploads.UploadLimits
import com.calypsan.listenup.client.domain.repository.UploadCandidate

/**
 * Why a selection was refused before a single byte went out.
 *
 * Checking client-side is a courtesy — the server enforces every one of these per request — but
 * discovering a 70 GiB selection is over the cap after uploading 60 of them is the difference
 * between a dialog and a wasted evening. Each case carries the limit it broke, so a client can say
 * the whole sentence without reaching for [UploadLimits] itself.
 */
sealed interface UploadSelectionRefusal {
    /** [count] files picked; one session carries at most [limit]. */
    data class TooManyFiles(
        val count: Int,
        val limit: Int,
    ) : UploadSelectionRefusal

    /** The selection totals [bytes]; one session carries at most [limitBytes]. */
    data class TooLarge(
        val bytes: Long,
        val limitBytes: Long,
    ) : UploadSelectionRefusal

    /** [filename] alone is [bytes]; a single file may be at most [limitBytes]. */
    data class FileTooLarge(
        val filename: String,
        val bytes: Long,
        val limitBytes: Long,
    ) : UploadSelectionRefusal
}

/**
 * The first cap [candidates] breaks, or null when the selection fits every one — the file count
 * first, then any single file, then the total.
 *
 * A file whose size is unknown (a cloud-backed provider may not say) counts as nothing: that is not
 * a reason to refuse, and the server still enforces the caps as the bytes arrive.
 */
fun uploadSelectionRefusal(candidates: List<UploadCandidate>): UploadSelectionRefusal? {
    if (candidates.size > UploadLimits.MAX_FILES) {
        return UploadSelectionRefusal.TooManyFiles(count = candidates.size, limit = UploadLimits.MAX_FILES)
    }
    candidates.firstOrNull { (it.source.size ?: 0L) > UploadLimits.MAX_FILE_BYTES }?.let {
        return UploadSelectionRefusal.FileTooLarge(
            filename = it.source.filename,
            bytes = it.source.size ?: 0L,
            limitBytes = UploadLimits.MAX_FILE_BYTES,
        )
    }
    val total = candidates.sumOf { it.source.size ?: 0L }
    return if (total > UploadLimits.MAX_SESSION_BYTES) {
        UploadSelectionRefusal.TooLarge(bytes = total, limitBytes = UploadLimits.MAX_SESSION_BYTES)
    } else {
        null
    }
}
