package com.calypsan.listenup.client.features.match

import androidx.compose.runtime.Composable
import com.calypsan.listenup.api.dto.match.EditionFormat
import com.calypsan.listenup.api.dto.match.FieldValue
import com.calypsan.listenup.api.dto.match.MatchReason
import com.calypsan.listenup.api.dto.match.MetadataSource
import com.calypsan.listenup.api.dto.match.SearchStep
import com.calypsan.listenup.api.metadata.BookField
import com.calypsan.listenup.client.core.DurationFormatter
import com.calypsan.listenup.client.presentation.match.CandidateUi
import com.calypsan.listenup.client.presentation.match.YourCopyUi
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.match_chapter_count_one
import listenup.composeapp.generated.resources.match_chapters_count
import listenup.composeapp.generated.resources.match_field_authors
import listenup.composeapp.generated.resources.match_field_description
import listenup.composeapp.generated.resources.match_field_language
import listenup.composeapp.generated.resources.match_field_narrators
import listenup.composeapp.generated.resources.match_field_publisher
import listenup.composeapp.generated.resources.match_field_release_date
import listenup.composeapp.generated.resources.match_field_series
import listenup.composeapp.generated.resources.match_field_subtitle
import listenup.composeapp.generated.resources.match_field_title
import listenup.composeapp.generated.resources.match_format_abridged
import listenup.composeapp.generated.resources.match_format_dramatized
import listenup.composeapp.generated.resources.match_format_unabridged
import listenup.composeapp.generated.resources.match_full_cast
import listenup.composeapp.generated.resources.match_genres
import listenup.composeapp.generated.resources.match_list_many
import listenup.composeapp.generated.resources.match_list_two
import listenup.composeapp.generated.resources.match_moods
import listenup.composeapp.generated.resources.match_reason_different_chapter_count
import listenup.composeapp.generated.resources.match_reason_different_edition
import listenup.composeapp.generated.resources.match_reason_different_narrators
import listenup.composeapp.generated.resources.match_reason_different_store
import listenup.composeapp.generated.resources.match_reason_length_unknown
import listenup.composeapp.generated.resources.match_reason_length_within
import listenup.composeapp.generated.resources.match_reason_longer
import listenup.composeapp.generated.resources.match_reason_same_chapter_count
import listenup.composeapp.generated.resources.match_reason_same_length
import listenup.composeapp.generated.resources.match_reason_same_narrator
import listenup.composeapp.generated.resources.match_reason_shorter
import listenup.composeapp.generated.resources.match_section_chapter_names
import listenup.composeapp.generated.resources.match_section_cover
import listenup.composeapp.generated.resources.match_series_entry
import listenup.composeapp.generated.resources.match_step_asin
import listenup.composeapp.generated.resources.match_step_existing_link
import listenup.composeapp.generated.resources.match_step_isbn
import listenup.composeapp.generated.resources.match_step_title_author_length
import listenup.composeapp.generated.resources.match_step_your_query
import listenup.composeapp.generated.resources.match_steps_join
import listenup.composeapp.generated.resources.match_steps_started_from
import org.jetbrains.compose.resources.stringResource
import kotlin.math.abs
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes

/** The separator between facts on one line: "Ray Porter · 16h 10m · 2021". */
internal const val DOT = " · "

/** "A", "A and B", "A, B and C". */
@Composable
internal fun joinedList(items: List<String>): String =
    when (items.size) {
        0 -> ""
        1 -> items.single()
        2 -> stringResource(Res.string.match_list_two, items[0], items[1])
        else -> stringResource(Res.string.match_list_many, items.first(), joinedList(items.drop(1)))
    }

/** Source labels as one phrase: "A, B and C". Labels are opaque; never branched on. */
@Composable
internal fun sourcesPhrase(sources: List<MetadataSource>): String = joinedList(sources.map { it.label }.distinct())

/** A length as the app writes one: "16h 10m". */
internal fun lengthText(durationMs: Long): String = DurationFormatter.hoursMinutes(durationMs.milliseconds)

/** "36 chapters" / "1 chapter". */
@Composable
internal fun chapterCountText(count: Int): String =
    if (count == 1) {
        stringResource(Res.string.match_chapter_count_one)
    } else {
        stringResource(Res.string.match_chapters_count, count)
    }

/** An edition's format name. */
@Composable
internal fun EditionFormat.displayName(): String =
    stringResource(
        when (this) {
            EditionFormat.UNABRIDGED -> Res.string.match_format_unabridged
            EditionFormat.ABRIDGED -> Res.string.match_format_abridged
            EditionFormat.DRAMATIZED -> Res.string.match_format_dramatized
        },
    )

/** A reviewed field's name as Review titles it. */
@Composable
internal fun BookField.displayName(): String =
    when (this) {
        BookField.TITLE -> stringResource(Res.string.match_field_title)

        BookField.SUBTITLE -> stringResource(Res.string.match_field_subtitle)

        BookField.DESCRIPTION -> stringResource(Res.string.match_field_description)

        BookField.PUBLISHER -> stringResource(Res.string.match_field_publisher)

        BookField.PUBLISH_YEAR -> stringResource(Res.string.match_field_release_date)

        BookField.LANGUAGE -> stringResource(Res.string.match_field_language)

        BookField.AUTHORS -> stringResource(Res.string.match_field_authors)

        BookField.NARRATORS -> stringResource(Res.string.match_field_narrators)

        BookField.SERIES -> stringResource(Res.string.match_field_series)

        BookField.GENRES -> stringResource(Res.string.match_genres)

        BookField.MOODS -> stringResource(Res.string.match_moods)

        BookField.COVER -> stringResource(Res.string.match_section_cover)

        BookField.CHAPTERS -> stringResource(Res.string.match_section_chapter_names)

        // Identifiers keep their own spelling; anything else reads as its words.
        BookField.ASIN, BookField.ISBN -> name

        else -> name.lowercase().replace('_', ' ').replaceFirstChar { it.uppercase() }
    }

/** A field value as one line of text. Description HTML reads as plain text, as Book Detail shows it. */
@Composable
internal fun FieldValue.displayText(): String =
    when (this) {
        is FieldValue.Text -> {
            text.asPlainText()
        }

        is FieldValue.People -> {
            names.joinToString(", ")
        }

        is FieldValue.Year -> {
            year.toString()
        }

        is FieldValue.SeriesEntries -> {
            entries
                .map { entry ->
                    entry.sequence?.let { stringResource(Res.string.match_series_entry, entry.name, it) } ?: entry.name
                }.joinToString(", ")
        }
    }

private val LINE_BREAK_TAGS = Regex("""<\s*(br|/p|/div|/li)\s*/?\s*>""", RegexOption.IGNORE_CASE)
private val ANY_TAG = Regex("""<[^>]+>""")
private val BLANK_LINES = Regex("""\n{3,}""")
private val ENTITIES =
    mapOf(
        "&amp;" to "&",
        "&lt;" to "<",
        "&gt;" to ">",
        "&quot;" to "\"",
        "&#39;" to "'",
        "&apos;" to "'",
        "&nbsp;" to " ",
    )

/** Catalogue descriptions arrive as HTML; Review reads them as the words alone. */
internal fun String.asPlainText(): String {
    if ('<' !in this && '&' !in this) return this
    var text = replace(LINE_BREAK_TAGS, "\n").replace(ANY_TAG, "")
    ENTITIES.forEach { (entity, character) -> text = text.replace(entity, character) }
    return text.replace(BLANK_LINES, "\n\n").trim()
}

/** One reason a candidate ranks where it does. */
@Composable
internal fun MatchReason.displayText(): String =
    when (this) {
        MatchReason.SameNarrator -> {
            stringResource(Res.string.match_reason_same_narrator)
        }

        MatchReason.DifferentNarrators -> {
            stringResource(Res.string.match_reason_different_narrators)
        }

        MatchReason.SameLength -> {
            stringResource(Res.string.match_reason_same_length)
        }

        MatchReason.LengthUnknown -> {
            stringResource(Res.string.match_reason_length_unknown)
        }

        is MatchReason.LengthWithin -> {
            stringResource(Res.string.match_reason_length_within, minutes)
        }

        is MatchReason.LengthDiffers -> {
            val amount = DurationFormatter.hoursMinutes(abs(deltaMinutes).minutes)
            if (deltaMinutes < 0) {
                stringResource(Res.string.match_reason_shorter, amount)
            } else {
                stringResource(Res.string.match_reason_longer, amount)
            }
        }

        is MatchReason.SameChapterCount -> {
            stringResource(Res.string.match_reason_same_chapter_count, count)
        }

        is MatchReason.DifferentChapterCount -> {
            stringResource(Res.string.match_reason_different_chapter_count, theirs)
        }

        is MatchReason.DifferentStore -> {
            stringResource(Res.string.match_reason_different_store, region.displayName)
        }

        is MatchReason.DifferentEdition -> {
            stringResource(Res.string.match_reason_different_edition, format.displayName())
        }
    }

/** A candidate's facts: "Ray Porter · 16h 10m · 2021 · Unabridged". A full-cast reading names no narrator. */
@Composable
internal fun CandidateUi.factsLine(): String {
    val who =
        when {
            narrators.isNotEmpty() -> narrators.joinToString(", ")
            format == EditionFormat.DRAMATIZED -> stringResource(Res.string.match_full_cast)
            else -> null
        }
    return listOfNotNull(
        who,
        durationMs?.let(::lengthText),
        year?.toString(),
        format?.takeIf { it != EditionFormat.DRAMATIZED }?.displayName(),
    ).joinToString(DOT)
}

/** Your copy's facts: "16h 10m · Ray Porter · 36 chapters". */
@Composable
internal fun YourCopyUi.factsLine(): String =
    listOfNotNull(
        durationMs?.let(::lengthText),
        narrators.takeIf { it.isNotEmpty() }?.joinToString(", "),
        chapterCount?.let { chapterCountText(it) },
    ).joinToString(DOT)

/** "Started from your <source> link, then title, author and length." — null when Find took no steps. */
@Composable
internal fun stepsLine(steps: List<SearchStep>): String? {
    if (steps.isEmpty()) return null
    val phrases = steps.map { it.phrase() }
    var sentence = phrases.first()
    phrases.drop(1).forEach { next -> sentence = stringResource(Res.string.match_steps_join, sentence, next) }
    return stringResource(Res.string.match_steps_started_from, sentence)
}

@Composable
private fun SearchStep.phrase(): String =
    when (this) {
        is SearchStep.ExistingLink -> {
            stringResource(Res.string.match_step_existing_link, source.label)
        }

        is SearchStep.Identifier -> {
            stringResource(
                when (kind) {
                    com.calypsan.listenup.api.dto.match.IdentifierKind.ASIN -> Res.string.match_step_asin
                    com.calypsan.listenup.api.dto.match.IdentifierKind.ISBN -> Res.string.match_step_isbn
                },
            )
        }

        SearchStep.TitleAuthorLength -> {
            stringResource(Res.string.match_step_title_author_length)
        }

        is SearchStep.YourQuery -> {
            stringResource(Res.string.match_step_your_query, query)
        }
    }
