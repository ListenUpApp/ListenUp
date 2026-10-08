package com.calypsan.listenup.web.features.match

import com.calypsan.listenup.api.dto.ContributorRole
import com.calypsan.listenup.api.dto.match.LibraryCredit
import com.calypsan.listenup.api.dto.match.PersonSearchStep
import com.calypsan.listenup.client.presentation.match.InLibraryUi
import com.calypsan.listenup.client.presentation.match.MatchReceiptUi
import com.calypsan.listenup.client.presentation.match.PersonApplySummary
import com.calypsan.listenup.client.presentation.match.PersonCandidateUi

/*
 * Every sentence person Match details says on web, beside MatchWords.kt's book sentences: the English
 * text of en.json's `match` person keys, as plain functions of the shared UI types. Provider names are
 * never written here — a source is only ever its `label`.
 */

/** "Author", "Narrator" (match.role_*); any other role as its own word. */
internal fun roleLabel(role: ContributorRole): String =
    when (role) {
        ContributorRole.AUTHOR -> "Author"
        ContributorRole.NARRATOR -> "Narrator"
        else -> role.apiValue.replaceFirstChar { it.uppercase() }
    }

/** The search field's label (match.search_person_label). */
internal const val PERSON_SEARCH_LABEL = "Search for a person"

/** "1 person", "3 people" (match.people_count*). */
internal fun peopleCountText(count: Int): String = if (count == 1) "1 person" else "$count people"

/** "1 book", "12 books" (match.works_count*). */
internal fun worksCountText(count: Int): String = if (count == 1) "1 book" else "$count books"

/** One role's evidence: "Narrated 3", "Translated 1", "Wrote the foreword for 2" (match.credit_*). */
private fun creditText(credit: LibraryCredit): String {
    val verb =
        when (credit.role) {
            ContributorRole.AUTHOR -> "Wrote"
            ContributorRole.NARRATOR -> "Narrated"
            ContributorRole.EDITOR -> "Edited"
            ContributorRole.TRANSLATOR -> "Translated"
            ContributorRole.FOREWORD -> "Wrote the foreword for"
            ContributorRole.INTRODUCTION -> "Wrote the introduction for"
            ContributorRole.AFTERWORD -> "Wrote the afterword for"
            ContributorRole.PRODUCER -> "Produced"
            ContributorRole.ADAPTER -> "Adapted"
            ContributorRole.ILLUSTRATOR -> "Illustrated"
        }
    return "$verb ${credit.bookCount}"
}

/**
 * What someone did in your library, every role, most first: "Narrated 3 of your books · Translated 1"
 * (match.credit_*, credit_of_your_books). Null when they did nothing here.
 */
internal fun creditsText(credits: List<LibraryCredit>): String? {
    if (credits.isEmpty()) return null
    val lead = "${creditText(credits.first())} of your books"
    return (listOf(lead) + credits.drop(1).map(::creditText)).joinToString(" · ")
}

/** The Your-library strip: "Narrated 5 of your books · Wrote 1: Project Hail Mary, The Martian, Artemis". */
internal fun inLibraryText(inLibrary: InLibraryUi): String {
    val line = creditsText(inLibrary.credits) ?: NO_BOOKS_IN_LIBRARY
    return if (inLibrary.bookCount > 0 && inLibrary.titles.isNotEmpty()) {
        "$line: ${inLibrary.titles.joinToString(", ")}"
    } else {
        line
    }
}

/**
 * "Started from the 3 books crediting Andy Weir in your library." — or null when Find took no steps
 * (match.steps_started_from, steps_join, step_existing_link, step_via_books*, step_by_name).
 */
internal fun personStepsText(
    steps: List<PersonSearchStep>,
    name: String,
): String? {
    if (steps.isEmpty()) return null
    val said = steps.map { personStepText(it, name) }.reduce { first, next -> "$first, then $next" }
    return "Started from $said."
}

private fun personStepText(
    step: PersonSearchStep,
    name: String,
): String =
    when (step) {
        is PersonSearchStep.ExistingLink -> {
            "your ${step.source.label} link"
        }

        is PersonSearchStep.ViaYourBooks -> {
            if (step.bookCount == 1) {
                "the book crediting $name in your library"
            } else {
                "the ${step.bookCount} books crediting $name in your library"
            }
        }

        is PersonSearchStep.ByName -> {
            "a search for “${step.query}”"
        }
    }

/** A person row's second line: "Author · The Martian, Artemis", or "Author · 1 book" when no works are known. */
internal fun personMetaText(candidate: PersonCandidateUi): String =
    listOfNotNull(
        candidate.shownRole?.let(::roleLabel),
        candidate.knownWorks.takeIf { it.isNotEmpty() }?.joinToString(", ")
            ?: candidate.worksCount?.let(::worksCountText),
    ).joinToString(" · ")

/** A person row's evidence: "Narrated 3 of your books · Translated 1", "No books in your library", or null. */
internal fun personLibraryText(candidate: PersonCandidateUi): String? =
    creditsText(candidate.libraryCredits) ?: NO_BOOKS_IN_LIBRARY.takeIf { candidate.noBooksInLibrary }

/**
 * A person row's accessible name, saying all of it in order (match.person_row_a11y): "Best match. Andy Weir.
 * Author · The Martian, Artemis. Wrote 3 of your books. Found in Audible and Hardcover."
 */
internal fun personRowName(candidate: PersonCandidateUi): String =
    listOfNotNull(
        BEST_MATCH.takeIf { candidate.isBest },
        "Your current link".takeIf { candidate.isCurrentLink },
        candidate.name,
        personMetaText(candidate).takeIf { it.isNotEmpty() },
        personLibraryText(candidate),
        candidate.foundIn.takeIf { it.isNotEmpty() }?.let { "Found in ${sourcesText(it)}" },
    ).joinToString(". ", postfix = ".")

/** "Narrator · from Hardcover" under the Review header's name (match.person_header_from, person_header_found_in). */
internal fun personHeaderFromText(candidate: PersonCandidateUi): String {
    val role = candidate.shownRole?.let(::roleLabel)
    val from = candidate.foundIn.takeIf { it.isNotEmpty() }?.let(::sourcesText)
    return when {
        role != null && from != null -> "$role · from $from"
        role != null -> role
        from != null -> "From $from"
        else -> ""
    }
}

/** "No source has a profile for this person" (match.no_profiles_title). */
internal const val NO_PROFILES_TITLE = "No source has a profile for this person"

/** The Apply bar's words: "Photo · biography", "Photo", "Biography", or "Nothing selected" (match.bar_*). */
internal fun personApplyBarText(summary: PersonApplySummary): String =
    when {
        summary.photo && summary.biography -> "Photo · biography"
        summary.photo -> "Photo"
        summary.biography -> "Biography"
        else -> "Nothing selected"
    }

/** "Changed photo and biography for Ray Porter" (match.receipt_person_*). */
internal fun personReceiptText(
    receipt: MatchReceiptUi,
    name: String,
): String =
    when {
        receipt.photoSource != null && receipt.biographySource != null -> "Changed photo and biography for $name"
        receipt.photoSource != null -> "Changed photo for $name"
        receipt.biographySource != null -> "Changed biography for $name"
        else -> "Matched $name. Nothing needed changing."
    }

internal const val NO_BOOKS_IN_LIBRARY = "No books in your library"
internal const val NO_PROFILES_BODY = "You can add their photo and biography yourself."
internal const val EDIT_BY_HAND = "Edit by hand"
internal const val REVIEW_RELOADED_PERSON = "This person changed while you were reviewing. Check the changes again."
internal const val NOTHING_CHANGES_UNTIL_APPLY = "Nothing changes until you apply."
