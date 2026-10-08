package com.calypsan.listenup.client.features.match

import androidx.compose.runtime.Composable
import com.calypsan.listenup.api.dto.ContributorRole
import com.calypsan.listenup.api.dto.match.LibraryCredit
import com.calypsan.listenup.api.dto.match.PersonSearchStep
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
import listenup.composeapp.generated.resources.match_credit_adapter
import listenup.composeapp.generated.resources.match_credit_afterword
import listenup.composeapp.generated.resources.match_credit_author
import listenup.composeapp.generated.resources.match_credit_editor
import listenup.composeapp.generated.resources.match_credit_foreword
import listenup.composeapp.generated.resources.match_credit_illustrator
import listenup.composeapp.generated.resources.match_credit_introduction
import listenup.composeapp.generated.resources.match_credit_narrator
import listenup.composeapp.generated.resources.match_credit_of_your_books
import listenup.composeapp.generated.resources.match_credit_producer
import listenup.composeapp.generated.resources.match_credit_translator
import listenup.composeapp.generated.resources.match_step_via_books
import listenup.composeapp.generated.resources.match_step_via_books_one
import listenup.composeapp.generated.resources.match_in_library_titles
import listenup.composeapp.generated.resources.match_no_books_in_library
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
import listenup.composeapp.generated.resources.match_steps_join
import listenup.composeapp.generated.resources.match_steps_started_from
import listenup.composeapp.generated.resources.match_works_count
import listenup.composeapp.generated.resources.match_works_count_one
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

/** One role's evidence: "Narrated 3", "Translated 1", "Wrote the foreword for 2". */
@Composable
private fun creditPhrase(credit: LibraryCredit): String =
    stringResource(
        when (credit.role) {
            ContributorRole.AUTHOR -> Res.string.match_credit_author
            ContributorRole.NARRATOR -> Res.string.match_credit_narrator
            ContributorRole.EDITOR -> Res.string.match_credit_editor
            ContributorRole.TRANSLATOR -> Res.string.match_credit_translator
            ContributorRole.FOREWORD -> Res.string.match_credit_foreword
            ContributorRole.INTRODUCTION -> Res.string.match_credit_introduction
            ContributorRole.AFTERWORD -> Res.string.match_credit_afterword
            ContributorRole.PRODUCER -> Res.string.match_credit_producer
            ContributorRole.ADAPTER -> Res.string.match_credit_adapter
            ContributorRole.ILLUSTRATOR -> Res.string.match_credit_illustrator
        },
        credit.bookCount,
    )

/**
 * What someone did in your library, every role, most first: "Narrated 3 of your books · Translated 1". Null when
 * they did nothing here.
 */
@Composable
internal fun creditsLine(credits: List<LibraryCredit>): String? {
    if (credits.isEmpty()) return null
    val lead = stringResource(Res.string.match_credit_of_your_books, creditPhrase(credits.first()))
    return (listOf(lead) + credits.drop(1).map { creditPhrase(it) }).joinToString(DOT)
}

/**
 * "Narrated 5 of your books · Wrote 1: Project Hail Mary, The Martian, Artemis". The titles are a sample of at
 * most three, so they're listed, never joined with "and" as if they were all of them.
 */
@Composable
internal fun InLibraryUi.sentence(): String {
    val line = creditsLine(credits) ?: stringResource(Res.string.match_no_books_in_library)
    return if (titles.isEmpty()) {
        line
    } else {
        stringResource(
            Res.string.match_in_library_titles,
            line,
            titles.joinToString(", "),
        )
    }
}

/** "Started from the 5 books crediting Ray Porter in your library." — null when Find took no steps. */
@Composable
internal fun personStepsLine(
    steps: List<PersonSearchStep>,
    name: String,
): String? {
    if (steps.isEmpty()) return null
    val phrases = steps.map { it.phrase(name) }
    var sentence = phrases.first()
    phrases.drop(1).forEach { next -> sentence = stringResource(Res.string.match_steps_join, sentence, next) }
    return stringResource(Res.string.match_steps_started_from, sentence)
}

@Composable
private fun PersonSearchStep.phrase(name: String): String =
    when (this) {
        is PersonSearchStep.ExistingLink -> {
            stringResource(Res.string.match_step_existing_link, source.label)
        }

        is PersonSearchStep.ViaYourBooks -> {
            if (bookCount == 1) {
                stringResource(Res.string.match_step_via_books_one, name)
            } else {
                stringResource(Res.string.match_step_via_books, bookCount, name)
            }
        }

        is PersonSearchStep.ByName -> {
            stringResource(Res.string.match_step_by_name, query)
        }
    }

/** "3 people", read out when a people search lands. */
@Composable
internal fun peopleCount(count: Int): String =
    if (count ==
        1
    ) {
        stringResource(Res.string.match_people_count_one)
    } else {
        stringResource(Res.string.match_people_count, count)
    }

/** A person row's second line: "Narrator · Project Hail Mary, Bobiverse", "Author · 1 book". */
@Composable
internal fun PersonCandidateUi.roleLine(): String {
    val works =
        knownWorks.takeIf { it.isNotEmpty() }?.take(KNOWN_WORKS_SHOWN)?.joinToString(", ")
            ?: worksCount?.let {
                if (it ==
                    1
                ) {
                    stringResource(Res.string.match_works_count_one)
                } else {
                    stringResource(Res.string.match_works_count, it)
                }
            }
    return listOfNotNull(shownRole?.let { roleName(it) }, works).joinToString(DOT)
}

/** "Narrated 3 of your books · Translated 1", "No books in your library", or nothing to say. */
@Composable
internal fun PersonCandidateUi.libraryLine(): String? =
    creditsLine(libraryCredits)
        ?: if (noBooksInLibrary) stringResource(Res.string.match_no_books_in_library) else null

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
