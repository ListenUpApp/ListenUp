package com.calypsan.listenup.server.matching.review

import com.calypsan.listenup.api.dto.match.ChapterNameChange
import com.calypsan.listenup.api.dto.match.ChapterNamesReview
import com.calypsan.listenup.api.sync.BookChapterPayload
import com.calypsan.listenup.server.metadata.spi.ChapterListMeta
import com.calypsan.listenup.server.metadata.spi.MetadataProviderId
import com.calypsan.listenup.server.metadata.spi.toMetadataSource

/** Chapter names Review offers: the source and its names in start-time order, when the counts line up. */
internal data class ReviewedChapters(
    val provider: MetadataProviderId,
    val names: List<String?>,
)

/**
 * The chapter-names half of Review (spec, *Review*, step 5), read exactly as `ChapterNameApplier` reads it: both
 * lists in start-time order, aligned by ordinal only when the counts match. Only names that differ are listed.
 */
internal object ChapterNamesReviewer {
    fun review(
        yours: List<BookChapterPayload>,
        composed: Pair<MetadataProviderId, ChapterListMeta>?,
    ): Pair<ChapterNamesReview, ReviewedChapters?> {
        val (provider, list) = composed ?: return ChapterNamesReview.Unavailable to null
        val source = provider.toMetadataSource()
        val local = yours.sortedBy { it.startTime }
        val remote = list.chapters.sortedBy { it.startMs }
        if (local.size != remote.size) {
            return ChapterNamesReview.CountMismatch(source, local.size, remote.size) to null
        }
        val names = remote.map { it.title?.run { trim().takeIf(String::isNotEmpty) } }
        val rows =
            local.mapIndexedNotNull { ordinal, chapter ->
                val theirs = names[ordinal] ?: return@mapIndexedNotNull null
                if (theirs == chapter.title.trim()) null else ChapterNameChange(ordinal, chapter.title, theirs)
            }
        return ChapterNamesReview.Available(source, rows, local.size - rows.size) to ReviewedChapters(provider, names)
    }
}
