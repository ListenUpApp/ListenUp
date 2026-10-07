package com.calypsan.listenup.server.matching.apply

import com.calypsan.listenup.api.dto.ContributorRole
import com.calypsan.listenup.api.dto.match.AppliedChange
import com.calypsan.listenup.api.error.MetadataError
import com.calypsan.listenup.api.metadata.BookField
import com.calypsan.listenup.api.metadata.FieldProvenance
import com.calypsan.listenup.api.metadata.FieldSourceKind
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.sync.BookContributorPayload
import com.calypsan.listenup.api.sync.BookSeriesPayload
import com.calypsan.listenup.api.sync.BookSyncPayload
import com.calypsan.listenup.api.sync.ChapterSource
import com.calypsan.listenup.api.sync.CoverPayload
import com.calypsan.listenup.api.sync.CoverSource
import com.calypsan.listenup.api.sync.parseSeriesSequence
import com.calypsan.listenup.server.logging.loggerFor
import com.calypsan.listenup.server.matching.review.BookMood
import com.calypsan.listenup.server.matching.review.MoodLabelIdentity
import com.calypsan.listenup.server.matching.review.ReviewKeys
import com.calypsan.listenup.server.metadata.spi.SeriesMeta
import com.calypsan.listenup.server.services.MatchCoverColumns

private val log = loggerFor<MatchPreparer>()

/** A match ready for its one transaction: every catalogue row resolved, the cover file stored. */
internal data class MatchWritePlan(
    val book: BookSyncPayload,
    val cover: MatchCoverColumns?,
    val genreIds: List<String>?,
    val ladderRungIds: List<String>,
    val moodsToLink: List<String>,
    val moodsToUnlink: List<String>,
    val changes: List<AppliedChange>,
)

/**
 * Where a match's names become catalogue ids. Each may create a row (in its own transaction), which is why this
 * runs before the match's transaction — as the scanner pre-resolves. A failed apply can leave an unused new
 * contributor, series, genre or mood row; every link to it rolls back.
 */
internal class MatchCatalogs(
    val contributorId: suspend (String) -> String,
    val seriesId: suspend (String) -> String,
    val genreIds: suspend (String) -> List<String>,
    val ladderRungs: suspend (List<String>) -> List<String>,
    val moodId: suspend (String) -> String?,
)

/** Fetches and stores a cover for a book, returning its columns; null when it can't (logged by the caller). */
internal fun interface MatchCoverStore {
    suspend fun store(
        bookId: String,
        url: String,
    ): MatchCoverColumns?
}

/**
 * Resolves a [MatchDraft] into a [MatchWritePlan] against [book] as it is now (spec, *Apply*, step 2): names to
 * ids, the cover's bytes fetched and stored before any transaction. A required cover that can't be fetched is
 * [MetadataError.CoverDownloadFailed] with nothing written; a best-effort one (the legacy apply) is dropped.
 */
internal class MatchPreparer(
    private val catalogs: MatchCatalogs,
    private val covers: MatchCoverStore,
    private val now: () -> Long,
) {
    suspend fun prepare(
        draft: MatchDraft,
        book: BookSyncPayload,
        currentMoods: List<BookMood>,
    ): AppResult<MatchWritePlan> {
        var changes = draft.changes
        val cover =
            draft.cover?.let { wanted ->
                covers.store(book.id, wanted.url) ?: run {
                    if (wanted.required) {
                        return AppResult.Failure(MetadataError.CoverDownloadFailed(debugInfo = "cover fetch or store failed"))
                    }
                    log.warn { "Match cover for ${book.id} couldn't be fetched — applying without it" }
                    changes = changes.filterNot { it is AppliedChange.Cover }
                    null
                }
            }
        val at = now()
        val stamps =
            draft.provenance.mapValues { (_, provider) ->
                FieldProvenance(FieldSourceKind.ENRICHMENT, provider = provider.value, at = at)
            } +
                listOfNotNull(
                    cover?.let { BookField.COVER to FieldProvenance(FieldSourceKind.ENRICHMENT, draft.cover.provider.value, at) },
                )
        val updated =
            book.withTexts(draft.texts).withYear(draft).copy(
                contributors = roles(book.contributors, draft),
                series = draft.series?.let { series(it) } ?: book.series,
                externalRefs =
                    book.externalRefs.filter { ref -> draft.refs.none { it.provider == ref.provider } } + draft.refs,
                asin = draft.asin ?: book.asin,
                cover = cover?.let { CoverPayload(it.source, requireNotNull(it.hash)) } ?: book.cover,
                fieldProvenance = book.fieldProvenance + stamps,
            ).withChapterTitles(draft.chapterTitles)
        val (link, unlink) = moods(draft.moods, currentMoods)
        return AppResult.Success(
            MatchWritePlan(
                book = updated,
                cover = cover,
                genreIds = genreIds(draft.genres, book),
                ladderRungIds = draft.ladders.flatMap { catalogs.ladderRungs(it) }.distinct(),
                moodsToLink = link,
                moodsToUnlink = unlink,
                changes = changes,
            ),
        )
    }

    private fun BookSyncPayload.withTexts(texts: Map<BookField, String?>): BookSyncPayload =
        copy(
            title = texts[BookField.TITLE]?.takeIf { it.isNotBlank() } ?: title,
            subtitle = if (BookField.SUBTITLE in texts) texts[BookField.SUBTITLE] else subtitle,
            description = if (BookField.DESCRIPTION in texts) texts[BookField.DESCRIPTION] else description,
            publisher = if (BookField.PUBLISHER in texts) texts[BookField.PUBLISHER] else publisher,
            language = if (BookField.LANGUAGE in texts) texts[BookField.LANGUAGE] else language,
        )

    /** The year, and the provider's full date with it (the repository keeps the two in step). */
    private fun BookSyncPayload.withYear(draft: MatchDraft): BookSyncPayload =
        draft.year?.let { copy(publishYear = it.year, releaseDate = it.releaseDate) } ?: this

    private fun BookSyncPayload.withChapterTitles(titles: Map<Int, String>): BookSyncPayload {
        if (titles.isEmpty()) return this
        val ordered = chapters.sortedBy { it.startTime }
        return copy(
            chapters = ordered.mapIndexed { ordinal, chapter -> titles[ordinal]?.let { chapter.copy(title = it) } ?: chapter },
            chapterSource = ChapterSource.USER,
        )
    }

    /** Each written role replaced as a whole by the chosen source's list; other roles untouched. */
    private suspend fun roles(
        current: List<BookContributorPayload>,
        draft: MatchDraft,
    ): List<BookContributorPayload> {
        var merged = current
        suspend fun replace(
            role: ContributorRole,
            names: List<String>?,
        ) {
            names ?: return
            val credits =
                names.map { BookContributorPayload(catalogs.contributorId(it), it, null, role.apiValue, null) }
            merged = merged.filterNot { it.role.equals(role.apiValue, ignoreCase = true) } + credits
        }
        replace(ContributorRole.AUTHOR, draft.authors)
        replace(ContributorRole.NARRATOR, draft.narrators)
        return merged
    }

    private suspend fun series(entries: List<SeriesMeta>): List<BookSeriesPayload> =
        entries.map { BookSeriesPayload(catalogs.seriesId(it.title), it.title, parseSeriesSequence(it.sequence)) }

    /** The book's whole genre set after the match, or null when it leaves genres alone. */
    private suspend fun genreIds(
        plan: LabelPlan,
        book: BookSyncPayload,
    ): List<String>? =
        when (plan) {
            LabelPlan.Unchanged -> {
                null
            }

            is LabelPlan.ReplaceAll -> {
                plan.labels.distinctBy(ReviewKeys::text).flatMap { catalogs.genreIds(it) }.distinct()
            }

            is LabelPlan.AddRemove -> {
                val removed = book.genres.filter { g -> plan.remove.any { ReviewKeys.text(it) == ReviewKeys.text(g.name) } }
                (book.genres.map { it.id } - removed.map { it.id }.toSet() + plan.add.flatMap { catalogs.genreIds(it) })
                    .distinct()
            }
        }

    /** Mood ids to link and to unlink, measured against the book's live moods. */
    private suspend fun moods(
        plan: LabelPlan,
        current: List<BookMood>,
    ): Pair<List<String>, List<String>> {
        val currentIds = current.map { it.id }.toSet()
        return when (plan) {
            LabelPlan.Unchanged -> {
                emptyList<String>() to emptyList()
            }

            is LabelPlan.ReplaceAll -> {
                val target = plan.labels.mapNotNull { catalogs.moodId(it) }.toSet()
                (target - currentIds).toList() to (currentIds - target).toList()
            }

            is LabelPlan.AddRemove -> {
                val unlink =
                    current.filter { MoodLabelIdentity.same(it.name, plan.remove) }.map { it.id }
                val link = plan.add.mapNotNull { catalogs.moodId(it) }.filterNot { it in currentIds }.distinct()
                link to unlink
            }
        }
    }
}

/** A stored cover as the book's columns name it: hash-named so it never overwrites the file Undo restores. */
internal fun managedMatchCover(
    relPath: String,
    hash: String,
): MatchCoverColumns = MatchCoverColumns(CoverSource.UPLOADED, relPath, hash)
