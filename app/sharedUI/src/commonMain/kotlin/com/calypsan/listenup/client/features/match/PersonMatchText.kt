package com.calypsan.listenup.client.features.match

import androidx.compose.runtime.Composable
import com.calypsan.listenup.api.dto.ContributorRole
import com.calypsan.listenup.api.dto.match.PersonSearchStep
import com.calypsan.listenup.client.presentation.match.CoverageNote
import com.calypsan.listenup.client.presentation.match.InLibraryUi
import com.calypsan.listenup.client.presentation.match.MatchReceiptUi
import com.calypsan.listenup.client.presentation.match.PersonApplySummary
import com.calypsan.listenup.client.presentation.match.PersonCandidateUi
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.book_role_adapter
import listenup.composeapp.generated.resources.book_role_afterword
import listenup.composeapp.generated.resources.book_role_editor
import listenup.composeapp.generated.resources.book_role_foreword
import listenup.composeapp.generated.resources.book_role_illustrator
import listenup.composeapp.generated.resources.book_role_introduction
import listenup.composeapp.generated.resources.book_role_producer
import listenup.composeapp.generated.resources.book_role_translator
import listenup.composeapp.generated.resources.match_bar_biography
import listenup.composeapp.generated.resources.match_bar_nothing
import listenup.composeapp.generated.resources.match_bar_photo
import listenup.composeapp.generated.resources.match_bar_photo_and_biography
import listenup.composeapp.generated.resources.match_coverage_note_author
import listenup.composeapp.generated.resources.match_coverage_note_narrator
import listenup.composeapp.generated.resources.match_in_library_titles
import listenup.composeapp.generated.resources.match_narrated_in_library
import listenup.composeapp.generated.resources.match_narrated_in_library_one
import listenup.composeapp.generated.resources.match_no_books_in_library
import listenup.composeapp.generated.resources.match_not_a_narrator
import listenup.composeapp.generated.resources.match_not_an_author
import listenup.composeapp.generated.resources.match_people_count
import listenup.composeapp.generated.resources.match_people_count_one
import listenup.composeapp.generated.resources.match_receipt_person_biography
import listenup.composeapp.generated.resources.match_receipt_person_nothing
import listenup.composeapp.generated.resources.match_receipt_person_photo
import listenup.composeapp.generated.resources.match_receipt_person_photo_and_biography
import listenup.composeapp.generated.resources.match_role_author
import listenup.composeapp.generated.resources.match_role_narrator
import listenup.composeapp.generated.resources.match_step_by_name
import listenup.composeapp.generated.resources.match_step_existing_link
import listenup.composeapp.generated.resources.match_step_via_books_author
import listenup.composeapp.generated.resources.match_step_via_books_author_one
import listenup.composeapp.generated.resources.match_step_via_books_narrator
import listenup.composeapp.generated.resources.match_step_via_books_narrator_one
import listenup.composeapp.generated.resources.match_steps_join
import listenup.composeapp.generated.resources.match_steps_started_from
import listenup.composeapp.generated.resources.match_subtitle_author
import listenup.composeapp.generated.resources.match_subtitle_narrator
import listenup.composeapp.generated.resources.match_works_count
import listenup.composeapp.generated.resources.match_works_count_one
import listenup.composeapp.generated.resources.match_wrote_in_library
import listenup.composeapp.generated.resources.match_wrote_in_library_one
import org.jetbrains.compose.resources.stringResource

/** Known works a person row names before it stops: "Narrator · Project Hail Mary, Bobiverse". */
private const val KNOWN_WORKS_SHOWN = 2

/** A role as a row names it: "Author", "Narrator", "Translator". */
@Composable
internal fun roleName(role: ContributorRole): String =
    stringResource(
        when (role) {
            ContributorRole.AUTHOR -> Res.string.match_role_author
            ContributorRole.NARRATOR -> Res.string.match_role_narrator
            ContributorRole.EDITOR -> Res.string.book_role_editor
            ContributorRole.TRANSLATOR -> Res.string.book_role_translator
            ContributorRole.FOREWORD -> Res.string.book_role_foreword
            ContributorRole.INTRODUCTION -> Res.string.book_role_introduction
            ContributorRole.AFTERWORD -> Res.string.book_role_afterword
            ContributorRole.PRODUCER -> Res.string.book_role_producer
            ContributorRole.ADAPTER -> Res.string.book_role_adapter
            ContributorRole.ILLUSTRATOR -> Res.string.book_role_illustrator
        },
    )

/** "Ray Porter · narrator" — the person and the role this Match details is for. */
@Composable
internal fun personSubtitle(
    name: String,
    role: ContributorRole,
): String =
    if (role == ContributorRole.NARRATOR) {
        stringResource(Res.string.match_subtitle_narrator, name)
    } else {
        stringResource(Res.string.match_subtitle_author, name)
    }

/** "Narrated 5 books in your library" / "Wrote 1 book in your library". */
@Composable
internal fun inLibraryLine(
    role: ContributorRole,
    count: Int,
): String =
    when {
        role == ContributorRole.NARRATOR && count == 1 -> stringResource(Res.string.match_narrated_in_library_one)
        role == ContributorRole.NARRATOR -> stringResource(Res.string.match_narrated_in_library, count)
        count == 1 -> stringResource(Res.string.match_wrote_in_library_one)
        else -> stringResource(Res.string.match_wrote_in_library, count)
    }

/**
 * "Wrote 3 books in your library: Project Hail Mary, The Martian, Artemis". The titles are a sample of at most
 * three, so they're listed, never joined with "and" as if they were all of them.
 */
@Composable
internal fun InLibraryUi.sentence(): String {
    val line = inLibraryLine(role, bookCount)
    return if (titles.isEmpty()) line else stringResource(Res.string.match_in_library_titles, line, titles.joinToString(", "))
}

/** "Atlas has no narrator profiles, so this search uses Beacon." */
@Composable
internal fun CoverageNote.text(role: ContributorRole): String =
    stringResource(
        if (role == ContributorRole.NARRATOR) {
            Res.string.match_coverage_note_narrator
        } else {
            Res.string.match_coverage_note_author
        },
        sourcesPhrase(withoutProfiles),
        sourcesPhrase(using),
    )

/** "Started from the 5 books Ray Porter narrates in your library." — null when Find took no steps. */
@Composable
internal fun personStepsLine(
    steps: List<PersonSearchStep>,
    name: String,
    role: ContributorRole,
): String? {
    if (steps.isEmpty()) return null
    val phrases = steps.map { it.phrase(name, role) }
    var sentence = phrases.first()
    phrases.drop(1).forEach { next -> sentence = stringResource(Res.string.match_steps_join, sentence, next) }
    return stringResource(Res.string.match_steps_started_from, sentence)
}

@Composable
private fun PersonSearchStep.phrase(
    name: String,
    role: ContributorRole,
): String =
    when (this) {
        is PersonSearchStep.ExistingLink -> {
            stringResource(Res.string.match_step_existing_link, source.label)
        }

        is PersonSearchStep.ViaYourBooks -> {
            val narrator = role == ContributorRole.NARRATOR
            when {
                bookCount == 1 && narrator -> stringResource(Res.string.match_step_via_books_narrator_one, name)
                bookCount == 1 -> stringResource(Res.string.match_step_via_books_author_one, name)
                narrator -> stringResource(Res.string.match_step_via_books_narrator, bookCount, name)
                else -> stringResource(Res.string.match_step_via_books_author, bookCount, name)
            }
        }

        is PersonSearchStep.ByName -> {
            stringResource(Res.string.match_step_by_name, query)
        }
    }

/** "3 people", read out when a people search lands. */
@Composable
internal fun peopleCount(count: Int): String =
    if (count == 1) stringResource(Res.string.match_people_count_one) else stringResource(Res.string.match_people_count, count)

/**
 * A person row's second line: "Narrator · Project Hail Mary, Bobiverse", "Author · 1 book", and on a row in the
 * wrong role "Author · 1 book · Not a narrator".
 */
@Composable
internal fun PersonCandidateUi.roleLine(searched: ContributorRole): String {
    val works =
        knownWorks.takeIf { it.isNotEmpty() }?.take(KNOWN_WORKS_SHOWN)?.joinToString(", ")
            ?: worksCount?.let {
                if (it == 1) stringResource(Res.string.match_works_count_one) else stringResource(Res.string.match_works_count, it)
            }
    val notInRole =
        if (isDifferentRole) {
            stringResource(
                if (searched == ContributorRole.NARRATOR) Res.string.match_not_a_narrator else Res.string.match_not_an_author,
            )
        } else {
            null
        }
    return listOfNotNull(shownRole?.let { roleName(it) }, works, notInRole).joinToString(DOT)
}

/** "Narrated 5 books in your library", "No books in your library", or nothing to say. */
@Composable
internal fun PersonCandidateUi.libraryLine(searched: ContributorRole): String? =
    when {
        libraryCount > 0 -> inLibraryLine(searched, libraryCount)
        noBooksInLibrary -> stringResource(Res.string.match_no_books_in_library)
        else -> null
    }

/** The person Apply bar: "Photo · biography", "Photo", "Biography", or "Nothing selected". */
@Composable
internal fun personApplySummaryText(summary: PersonApplySummary): String =
    stringResource(
        when {
            summary.photo && summary.biography -> Res.string.match_bar_photo_and_biography
            summary.photo -> Res.string.match_bar_photo
            summary.biography -> Res.string.match_bar_biography
            else -> Res.string.match_bar_nothing
        },
    )

/** "Changed photo and biography for Ray Porter", or "Matched Ray Porter. Nothing needed changing." */
@Composable
internal fun personReceiptText(
    receipt: MatchReceiptUi,
    name: String,
): String =
    stringResource(
        when {
            receipt.photoSource != null && receipt.biographySource != null -> {
                Res.string.match_receipt_person_photo_and_biography
            }

            receipt.photoSource != null -> {
                Res.string.match_receipt_person_photo
            }

            receipt.biographySource != null -> {
                Res.string.match_receipt_person_biography
            }

            else -> {
                Res.string.match_receipt_person_nothing
            }
        },
        name,
    )
