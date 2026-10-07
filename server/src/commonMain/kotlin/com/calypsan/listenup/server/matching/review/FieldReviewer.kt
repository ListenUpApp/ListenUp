package com.calypsan.listenup.server.matching.review

import com.calypsan.listenup.api.dto.ContributorRole
import com.calypsan.listenup.api.dto.match.FieldChoice
import com.calypsan.listenup.api.dto.match.FieldOption
import com.calypsan.listenup.api.dto.match.FieldReview
import com.calypsan.listenup.api.dto.match.FieldState
import com.calypsan.listenup.api.dto.match.FieldValue
import com.calypsan.listenup.api.dto.match.HandEdit
import com.calypsan.listenup.api.dto.match.MatchSeriesEntry
import com.calypsan.listenup.api.metadata.BookField
import com.calypsan.listenup.api.metadata.FieldProvenance
import com.calypsan.listenup.api.metadata.FieldSourceKind
import com.calypsan.listenup.api.sync.BookSyncPayload
import com.calypsan.listenup.server.metadata.ComposedOptions
import com.calypsan.listenup.server.metadata.spi.BookContributorMeta
import com.calypsan.listenup.server.metadata.spi.BookCoreMeta
import com.calypsan.listenup.server.metadata.spi.EnrichmentRoutes
import com.calypsan.listenup.server.metadata.spi.MetadataProviderId
import com.calypsan.listenup.server.metadata.spi.SeriesMeta
import com.calypsan.listenup.server.metadata.spi.presentedAs
import com.calypsan.listenup.server.metadata.spi.toMetadataSource
import com.calypsan.listenup.server.services.BookIdentityColumns

/** What Apply writes when an option is chosen — the provider's own value, before display shaping. */
internal sealed interface OptionWrite {
    /** A text field's value. */
    data class Text(
        val text: String,
    ) : OptionWrite

    /** A release year, and the provider's full date when it had one. */
    data class Year(
        val year: Int,
        val releaseDate: String?,
    ) : OptionWrite

    /** One role's whole credit list. */
    data class People(
        val names: List<String>,
    ) : OptionWrite

    /** The book's series placements. */
    data class Series(
        val entries: List<SeriesMeta>,
    ) : OptionWrite
}

/** One option as Review shows it, the providers that offered it (route order), and what Apply writes for it. */
internal data class ReviewedOption(
    val option: FieldOption,
    val providers: List<MetadataProviderId>,
    val write: OptionWrite,
)

/** One reviewed field, with its options' write values. */
internal data class ReviewedField(
    val review: FieldReview,
    val options: List<ReviewedOption>,
)

/**
 * The per-field half of Review (spec, *Field candidates*). Each field's options come from every provider on that
 * field's route, in route order, identical values (by [ReviewKeys]) collapsed into one option listing every
 * source. The state rules:
 *
 * | Yours | vs the route's first option | Hand-edited | State | Default |
 * |---|---|---|---|---|
 * | empty | — | — | FILLS_GAP | the first option |
 * | equal | — | any | SAME | KeepCurrent |
 * | differs | — | no | CHANGES | the first option |
 * | differs | — | yes | USER_EDITED | KeepCurrent |
 *
 * A field no provider has is not reviewed at all.
 */
internal object FieldReviewer {
    /** The fields Review offers, in display order. Length is never written; ISBN and ASIN are identity. */
    val REVIEWABLE: List<BookField> =
        listOf(
            BookField.TITLE,
            BookField.SUBTITLE,
            BookField.DESCRIPTION,
            BookField.PUBLISHER,
            BookField.PUBLISH_YEAR,
            BookField.LANGUAGE,
            BookField.AUTHORS,
            BookField.NARRATORS,
            BookField.SERIES,
        )

    fun review(
        book: BookSyncPayload,
        options: ComposedOptions,
        routes: EnrichmentRoutes,
        handEditOf: (FieldProvenance) -> HandEdit,
    ): List<ReviewedField> =
        REVIEWABLE.mapNotNull { field ->
            val reviewed = optionsFor(field, options, routes)
            if (reviewed.isEmpty()) return@mapNotNull null
            val current = currentValue(field, book)
            val html = field == BookField.DESCRIPTION
            val best = reviewed.first()
            val edit = book.fieldProvenance[field]?.takeIf { it.kind == FieldSourceKind.USER }
            val (state, choice) =
                when {
                    current == null -> {
                        FieldState.FILLS_GAP to FieldChoice.Option(best.option.optionId)
                    }

                    ReviewKeys.keyOf(current, html) == ReviewKeys.keyOf(best.option.value, html) -> {
                        FieldState.SAME to FieldChoice.KeepCurrent
                    }

                    edit != null -> {
                        FieldState.USER_EDITED to FieldChoice.KeepCurrent
                    }

                    else -> {
                        FieldState.CHANGES to FieldChoice.Option(best.option.optionId)
                    }
                }
            ReviewedField(
                review =
                    FieldReview(
                        field = field,
                        current = current,
                        options = reviewed.map { it.option },
                        defaultChoice = choice,
                        state = state,
                        handEdit = edit?.let(handEditOf),
                    ),
                options = reviewed,
            )
        }

    /** Every provider's value for [field] in route order, grouped by comparison key. */
    private fun optionsFor(
        field: BookField,
        options: ComposedOptions,
        routes: EnrichmentRoutes,
    ): List<ReviewedOption> {
        val html = field == BookField.DESCRIPTION
        val proposals =
            routes.orderFor(field).mapNotNull { provider ->
                proposal(field, provider, options)?.let { (value, write) -> Triple(provider, value, write) }
            }
        return proposals
            .groupBy { (_, value, _) -> ReviewKeys.keyOf(value, html) }
            .map { (key, group) ->
                val providers = group.map { it.first }
                val write =
                    group.first().third.let { first ->
                        if (first is OptionWrite.Year) {
                            first.copy(
                                releaseDate =
                                    group.firstNotNullOfOrNull { (it.third as OptionWrite.Year).releaseDate },
                            )
                        } else {
                            first
                        }
                    }
                ReviewedOption(
                    option =
                        FieldOption(
                            optionId = ReviewKeys.optionId(providers.first().presentedAs().value, key),
                            value = group.first().second,
                            sources = providers.map { it.toMetadataSource() }.distinctBy { it.id },
                        ),
                    providers = providers,
                    write = write,
                )
            }
    }

    private fun proposal(
        field: BookField,
        provider: MetadataProviderId,
        options: ComposedOptions,
    ): Pair<FieldValue, OptionWrite>? {
        if (field == BookField.SERIES) {
            val series = options.series[provider]?.filter { it.title.isNotBlank() }?.takeIf { it.isNotEmpty() }
            return series?.let { entries ->
                FieldValue.SeriesEntries(entries.map { MatchSeriesEntry(it.title.trim(), it.sequence?.trim()) }) to
                    OptionWrite.Series(entries)
            }
        }
        val core = options.cores[provider] ?: return null
        return when (field) {
            BookField.AUTHORS -> people(core.authors)
            BookField.NARRATORS -> people(core.narrators)
            BookField.PUBLISH_YEAR -> year(core.releaseDate)
            else -> text(field, core)
        }
    }

    private fun people(credits: List<BookContributorMeta>): Pair<FieldValue, OptionWrite>? {
        val names = credits.map { it.name.trim() }.filter { it.isNotEmpty() }.distinct()
        return names.takeIf { it.isNotEmpty() }?.let { FieldValue.People(it) to OptionWrite.People(it) }
    }

    private fun year(releaseDate: String?): Pair<FieldValue, OptionWrite>? {
        val year = ReviewKeys.yearOf(releaseDate) ?: return null
        return FieldValue.Year(year) to OptionWrite.Year(year, BookIdentityColumns.fullDateOrNull(releaseDate))
    }

    private fun text(
        field: BookField,
        core: BookCoreMeta,
    ): Pair<FieldValue, OptionWrite>? {
        val raw =
            when (field) {
                BookField.TITLE -> core.title
                BookField.SUBTITLE -> core.subtitle
                BookField.DESCRIPTION -> core.description
                BookField.PUBLISHER -> core.publisher
                BookField.LANGUAGE -> core.language
                else -> null
            }?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return FieldValue.Text(raw) to OptionWrite.Text(raw)
    }

    /** Your value for [field], or null when you have none. */
    fun currentValue(
        field: BookField,
        book: BookSyncPayload,
    ): FieldValue? =
        when (field) {
            BookField.TITLE -> {
                book.title.text()
            }

            BookField.SUBTITLE -> {
                book.subtitle.text()
            }

            BookField.DESCRIPTION -> {
                book.description.text()
            }

            BookField.PUBLISHER -> {
                book.publisher.text()
            }

            BookField.LANGUAGE -> {
                book.language.text()
            }

            BookField.PUBLISH_YEAR -> {
                book.publishYear?.let { FieldValue.Year(it) }
            }

            BookField.AUTHORS -> {
                book.credited(ContributorRole.AUTHOR)
            }

            BookField.NARRATORS -> {
                book.credited(ContributorRole.NARRATOR)
            }

            BookField.SERIES -> {
                book.series
                    .takeIf { it.isNotEmpty() }
                    ?.let { list ->
                        FieldValue.SeriesEntries(list.map { MatchSeriesEntry(it.name, it.sequence?.label()) })
                    }
            }

            else -> {
                null
            }
        }

    private fun String?.text(): FieldValue? = this?.trim()?.takeIf { it.isNotEmpty() }?.let { FieldValue.Text(it) }

    private fun BookSyncPayload.credited(role: ContributorRole): FieldValue? =
        contributors
            .filter { ContributorRole.fromApiValue(it.role) == role }
            .map { it.name }
            .takeIf { it.isNotEmpty() }
            ?.let { FieldValue.People(it) }

    /** A stored sequence as people write it: `1` for a whole number, `1.5` otherwise. */
    private fun Double.label(): String = if (this % 1.0 == 0.0) toLong().toString() else toString()
}
