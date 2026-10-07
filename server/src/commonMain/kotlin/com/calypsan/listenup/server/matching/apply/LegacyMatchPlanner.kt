package com.calypsan.listenup.server.matching.apply

import com.calypsan.listenup.api.dto.MetadataApplySelection
import com.calypsan.listenup.api.dto.MetadataBook
import com.calypsan.listenup.api.dto.MetadataContributorRef
import com.calypsan.listenup.api.dto.match.AppliedChange
import com.calypsan.listenup.api.metadata.BookField
import com.calypsan.listenup.api.sync.BookSyncPayload
import com.calypsan.listenup.server.matching.review.OptionWrite
import com.calypsan.listenup.server.matching.review.ReviewKeys
import com.calypsan.listenup.server.metadata.spi.MetadataProviderId
import com.calypsan.listenup.server.metadata.spi.SeriesMeta
import com.calypsan.listenup.server.metadata.spi.toMetadataSource
import com.calypsan.listenup.server.services.BookIdentityColumns

/**
 * The legacy `applyBookMetadata` as a [MatchDraft], so it runs through the same preparation and one-transaction
 * writer as Match details (spec, *What changes in the legacy apply*). Its contract is unchanged for the older
 * clients that call it: a ticked field is written (a ticked empty value clears it); a role is replaced only when
 * the selection resolves at least one of the match's credits (keyed by ASIN, else name); series only when one
 * is chosen; genres and moods are replaced by exactly the selection; the ASIN is always stamped; and a cover
 * that can't be fetched is skipped, never fatal. It no longer writes tags.
 */
internal object LegacyMatchPlanner {
    fun plan(
        book: BookSyncPayload,
        currentMoods: List<String>,
        match: MetadataBook,
        fieldProviders: Map<BookField, MetadataProviderId>,
        asin: String,
        selection: MetadataApplySelection,
        ladders: List<List<String>>,
    ): MatchDraft {
        val fallback = MetadataProviderId.AUDIBLE

        fun providerOf(field: BookField) = fieldProviders[field] ?: fallback
        val draft = MatchDraftBuilder()

        fun text(
            field: BookField,
            ticked: Boolean,
            value: String?,
        ) {
            if (!ticked) return
            draft.texts[field] = value
            stamp(draft, field, providerOf(field))
        }
        text(BookField.TITLE, selection.title, match.title)
        text(BookField.SUBTITLE, selection.subtitle, match.subtitle)
        text(BookField.DESCRIPTION, selection.description, match.description)
        text(BookField.PUBLISHER, selection.publisher, match.publisher)
        text(BookField.LANGUAGE, selection.language, match.language)
        if (selection.releaseDate) {
            ReviewKeys.yearOf(match.releaseDate)?.let { year ->
                draft.year = OptionWrite.Year(year, BookIdentityColumns.fullDateOrNull(match.releaseDate))
            }
            stamp(draft, BookField.PUBLISH_YEAR, providerOf(BookField.PUBLISH_YEAR))
        }
        match.authors.selected(selection.authorAsins).takeIf { it.isNotEmpty() }?.let {
            draft.authors = it
            stamp(draft, BookField.AUTHORS, providerOf(BookField.AUTHORS))
        }
        match.narrators.selected(selection.narratorAsins).takeIf { it.isNotEmpty() }?.let {
            draft.narrators = it
            stamp(draft, BookField.NARRATORS, providerOf(BookField.NARRATORS))
        }
        match.series
            .filter { it.asin != null && it.asin in selection.seriesAsins }
            .takeIf { it.isNotEmpty() }
            ?.let { chosen ->
                draft.series = chosen.map { SeriesMeta(key = it.asin, title = it.title, sequence = it.sequence) }
                stamp(draft, BookField.SERIES, providerOf(BookField.SERIES))
            }

        val genres = selection.genres.toList()
        draft.genres = LabelPlan.ReplaceAll(genres)
        diff(book.genres.map { it.name }, genres)?.let { (added, removed) ->
            draft.changes +=
                AppliedChange.Genres(added, removed)
        }
        if (genres.isNotEmpty()) {
            draft.provenance[BookField.GENRES] = providerOf(BookField.GENRES)
            draft.ladders = ladders
        }
        val moods = selection.moods.toList()
        draft.moods = LabelPlan.ReplaceAll(moods)
        diff(currentMoods, moods)?.let { (added, removed) -> draft.changes += AppliedChange.Moods(added, removed) }

        draft.asin = asin
        draft.provenance[BookField.ASIN] = providerOf(BookField.ASIN)
        val coverUrl = selection.coverUrl?.takeIf { selection.cover && it.isNotBlank() }
        if (coverUrl != null) {
            val provider = providerOf(BookField.COVER)
            draft.cover = DraftCover(coverUrl, provider, required = false)
            draft.changes += AppliedChange.Cover(provider.toMetadataSource())
        }
        return draft.build()
    }

    private fun stamp(
        draft: MatchDraftBuilder,
        field: BookField,
        provider: MetadataProviderId,
    ) {
        draft.provenance[field] = provider
        draft.changes += AppliedChange.Field(field, provider.toMetadataSource())
    }

    /** Selected credits, keyed by ASIN when present else name — narrators often have no ASIN. */
    private fun List<MetadataContributorRef>.selected(keys: Set<String>): List<String> =
        filter { (it.asin ?: it.name) in keys }.map { it.name }

    /** What replacing [current] with [target] adds and removes, or null when nothing changes. */
    private fun diff(
        current: List<String>,
        target: List<String>,
    ): Pair<List<String>, List<String>>? {
        val added = target.filter { t -> current.none { ReviewKeys.text(it) == ReviewKeys.text(t) } }
        val removed = current.filter { c -> target.none { ReviewKeys.text(it) == ReviewKeys.text(c) } }
        return (added to removed).takeIf { added.isNotEmpty() || removed.isNotEmpty() }
    }
}
