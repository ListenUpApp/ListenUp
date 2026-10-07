package com.calypsan.listenup.server.matching.apply

import com.calypsan.listenup.api.dto.match.AppliedChange
import com.calypsan.listenup.api.dto.match.BookMatchApply
import com.calypsan.listenup.api.dto.match.ChapterNamesReview
import com.calypsan.listenup.api.dto.match.ExternalRef
import com.calypsan.listenup.api.dto.match.FieldChoice
import com.calypsan.listenup.api.dto.match.ImageChoice
import com.calypsan.listenup.api.error.AppError
import com.calypsan.listenup.api.error.MetadataError
import com.calypsan.listenup.api.metadata.BookField
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.server.matching.review.BookReviewModel
import com.calypsan.listenup.server.matching.review.FieldReviewer
import com.calypsan.listenup.server.matching.review.OptionWrite
import com.calypsan.listenup.server.matching.review.ReviewKeys
import com.calypsan.listenup.server.matching.review.ReviewedLabel
import com.calypsan.listenup.server.metadata.spi.MetadataProviderId
import com.calypsan.listenup.server.metadata.spi.SeriesMeta
import com.calypsan.listenup.server.metadata.spi.toMetadataSource

/**
 * Turns a person's choices on a Review into a [MatchDraft] — pure, no writes (spec, *Apply*, step 1). The
 * Review is re-derived first ([model]), so the draft holds exactly what the person saw:
 *  - the book moved on since the Review ([BookMatchApply.basedOnRevision]), or a chosen option, cover or
 *    suggestion is no longer offered → [MetadataError.ReviewOutdated];
 *  - chapter names chosen but the counts no longer line up → [MetadataError.ChapterCountMismatch].
 *
 * Each role is replaced as a whole by the chosen source's list. Genres and moods are add/remove, never
 * replace-all. Every written field is stamped with the option's first provider. Every ref in the candidate key
 * is stamped.
 */
internal object MatchPlanner {
    fun plan(
        model: BookReviewModel,
        request: BookMatchApply,
        ladders: List<List<String>>,
    ): AppResult<MatchDraft> {
        if (request.basedOnRevision != model.book.revision || request.candidate != model.key) {
            return outdated("book revision ${model.book.revision}, review was based on ${request.basedOnRevision}")
        }
        val draft = MatchDraftBuilder()
        request.fields.forEach { decision ->
            val choice = decision.choice as? FieldChoice.Option ?: return@forEach
            if (decision.field !in FieldReviewer.REVIEWABLE) {
                return AppResult.Failure(MetadataError.Malformed(debugInfo = "${decision.field} is not reviewable"))
            }
            val option =
                model.fields
                    .firstOrNull { it.review.field == decision.field }
                    ?.options
                    ?.firstOrNull { it.option.optionId == choice.optionId }
                    ?: return outdated("option ${choice.optionId} for ${decision.field} is no longer offered")
            draft.field(decision.field, option.write, option.providers.first())
        }
        planCover(model, request.cover)?.let { return AppResult.Failure(it) }
        (request.cover as? ImageChoice.Candidate)?.let { chosen ->
            val tile = model.covers.first { it.candidate.optionId == chosen.optionId }
            draft.cover = DraftCover(tile.candidate.url, tile.provider, required = true)
            draft.changes += AppliedChange.Cover(tile.provider.toMetadataSource())
        }
        val genresAdded = offered(request.genres.add, model.genres) ?: return outdated("a genre suggestion vanished")
        val moodsAdded = offered(request.moods.add, model.moods) ?: return outdated("a mood suggestion vanished")
        val genresRemoved = yours(request.genres.remove, model.book.genres.map { it.name })
        val moodsRemoved = yours(request.moods.remove, model.currentMoods.map { it.name })
        if (genresAdded.isNotEmpty() || genresRemoved.isNotEmpty()) {
            draft.genres = LabelPlan.AddRemove(genresAdded.map { it.suggestion.label }, genresRemoved)
            draft.changes += AppliedChange.Genres(genresAdded.map { it.suggestion.label }, genresRemoved)
            genresAdded.firstOrNull()?.let { draft.provenance[BookField.GENRES] = it.providers.first() }
            draft.ladders =
                ladders.filter { ladder ->
                    ladder.any { rung ->
                        genresAdded.any { ReviewKeys.text(it.suggestion.label) == ReviewKeys.text(rung) }
                    }
                }
        }
        if (moodsAdded.isNotEmpty() || moodsRemoved.isNotEmpty()) {
            draft.moods = LabelPlan.AddRemove(moodsAdded.map { it.suggestion.label }, moodsRemoved)
            draft.changes += AppliedChange.Moods(moodsAdded.map { it.suggestion.label }, moodsRemoved)
            moodsAdded.firstOrNull()?.let { draft.provenance[BookField.MOODS] = it.providers.first() }
        }
        if (request.chapterOrdinals.isNotEmpty()) {
            planChapters(model, request.chapterOrdinals.toSet(), draft)?.let { return AppResult.Failure(it) }
        }
        draft.refs = model.key.refs
        draft.asin =
            model.key.refs
                .firstOrNull { it.provider == ExternalRef.AUDIBLE }
                ?.id
        return AppResult.Success(draft.build())
    }

    private fun planCover(
        model: BookReviewModel,
        choice: ImageChoice,
    ): AppError? =
        when (choice) {
            ImageChoice.KeepCurrent -> {
                null
            }

            is ImageChoice.Candidate -> {
                if (model.covers.none { it.candidate.optionId == choice.optionId }) {
                    MetadataError.ReviewOutdated(debugInfo = "cover ${choice.optionId} is no longer offered")
                } else {
                    null
                }
            }
        }

    private fun planChapters(
        model: BookReviewModel,
        ordinals: Set<Int>,
        draft: MatchDraftBuilder,
    ): AppError? {
        val chapters =
            model.chapters ?: return if (model.review.chapterNames is ChapterNamesReview.CountMismatch) {
                MetadataError.ChapterCountMismatch(debugInfo = "counts differ")
            } else {
                MetadataError.ReviewOutdated(debugInfo = "chapter names are no longer offered")
            }
        val yours = model.book.chapters.sortedBy { it.startTime }
        val renamed =
            ordinals
                .filter { it in chapters.names.indices }
                .mapNotNull { ordinal -> chapters.names[ordinal]?.let { ordinal to it } }
                .filter { (ordinal, name) -> name != yours[ordinal].title }
                .toMap()
        if (renamed.isNotEmpty()) {
            draft.chapterTitles = renamed
            draft.changes += AppliedChange.ChapterNames(renamed.size, chapters.provider.toMetadataSource())
        }
        return null
    }

    /** The reviewed suggestions [labels] names, or null when one is no longer offered. */
    private fun offered(
        labels: List<String>,
        suggestions: List<ReviewedLabel>,
    ): List<ReviewedLabel>? =
        labels.distinctBy(ReviewKeys::text).map { label ->
            suggestions.firstOrNull { ReviewKeys.text(it.suggestion.label) == ReviewKeys.text(label) } ?: return null
        }

    /** The labels of yours [labels] names (case and spacing aside); one you don't have is already gone. */
    private fun yours(
        labels: List<String>,
        current: List<String>,
    ): List<String> = current.filter { mine -> labels.any { ReviewKeys.text(it) == ReviewKeys.text(mine) } }

    private fun outdated(detail: String) = AppResult.Failure(MetadataError.ReviewOutdated(debugInfo = detail))
}

/** Accumulates a [MatchDraft] field by field. */
internal class MatchDraftBuilder {
    val texts = mutableMapOf<BookField, String?>()
    var year: OptionWrite.Year? = null
    var authors: List<String>? = null
    var narrators: List<String>? = null
    var series: List<SeriesMeta>? = null
    var refs: List<ExternalRef> = emptyList()
    var asin: String? = null
    val provenance = mutableMapOf<BookField, MetadataProviderId>()
    var cover: DraftCover? = null
    var genres: LabelPlan = LabelPlan.Unchanged
    var ladders: List<List<String>> = emptyList()
    var moods: LabelPlan = LabelPlan.Unchanged
    var chapterTitles: Map<Int, String> = emptyMap()
    val changes = mutableListOf<AppliedChange>()

    /** Writes [write] to [field], stamped with [provider]. */
    fun field(
        field: BookField,
        write: OptionWrite,
        provider: MetadataProviderId,
    ) {
        when (write) {
            is OptionWrite.Text -> texts[field] = write.text
            is OptionWrite.Year -> year = write
            is OptionWrite.People -> if (field == BookField.AUTHORS) authors = write.names else narrators = write.names
            is OptionWrite.Series -> series = write.entries
        }
        provenance[field] = provider
        changes += AppliedChange.Field(field, provider.toMetadataSource())
    }

    fun build(): MatchDraft =
        MatchDraft(
            texts = texts.toMap(),
            year = year,
            authors = authors,
            narrators = narrators,
            series = series,
            refs = refs,
            asin = asin,
            provenance = provenance.toMap(),
            cover = cover,
            genres = genres,
            ladders = ladders,
            moods = moods,
            chapterTitles = chapterTitles,
            changes = changes.toList(),
        )
}
