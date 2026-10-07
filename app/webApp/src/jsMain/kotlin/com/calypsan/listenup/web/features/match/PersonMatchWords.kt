package com.calypsan.listenup.web.features.match

import com.calypsan.listenup.api.dto.ContributorRole
import com.calypsan.listenup.api.dto.match.PersonSearchStep
import com.calypsan.listenup.client.presentation.match.CoverageNote
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

/** "Andy Weir · author" (match.subtitle_author / _narrator). */
internal fun personSubtitle(
    name: String,
    role: ContributorRole,
): String = "$name · ${roleLabel(role).lowercase()}"

/** The search field's label: a narrator search says so (match.search_person_label / search_narrator_label). */
internal fun personSearchLabel(role: ContributorRole): String =
    if (role == ContributorRole.NARRATOR) "Search for a narrator" else "Search for a person"

/** "1 person", "3 people" (match.people_count*). */
internal fun peopleCountText(count: Int): String = if (count == 1) "1 person" else "$count people"

/** "1 book", "12 books" (match.works_count*). */
internal fun worksCountText(count: Int): String = if (count == 1) "1 book" else "$count books"

/**
 * "Wrote 3 books in your library" / "Narrated 1 book in your library", or "No books in your library" for none
 * (match.wrote_in_library*, narrated_in_library*, no_books_in_library).
 */
internal fun libraryLineText(
    role: ContributorRole,
    count: Int,
): String {
    if (count <= 0) return NO_BOOKS_IN_LIBRARY
    val narrated = role == ContributorRole.NARRATOR
    return when {
        narrated && count == 1 -> "Narrated 1 book in your library"
        narrated -> "Narrated $count books in your library"
        count == 1 -> "Wrote 1 book in your library"
        else -> "Wrote $count books in your library"
    }
}

/** The Your-library strip: "Wrote 3 books in your library: Project Hail Mary, The Martian, Artemis". */
internal fun inLibraryText(inLibrary: InLibraryUi): String {
    val line = libraryLineText(inLibrary.role, inLibrary.bookCount)
    return if (inLibrary.bookCount > 0 && inLibrary.titles.isNotEmpty()) {
        "$line: ${inLibrary.titles.joinToString(", ")}"
    } else {
        line
    }
}

/** "Audible has no narrator profiles, so this search uses Hardcover." (match.coverage_note_*). */
internal fun coverageNoteText(
    note: CoverageNote,
    role: ContributorRole,
): String {
    val kind = if (role == ContributorRole.NARRATOR) "narrator" else "author"
    return "${sourcesText(note.withoutProfiles)} has no $kind profiles, so this search uses ${sourcesText(note.using)}."
}

/**
 * "Started from the 3 books Andy Weir wrote in your library." — or null when Find took no steps
 * (match.steps_started_from, steps_join, step_existing_link, step_via_books_*, step_by_name).
 */
internal fun personStepsText(
    steps: List<PersonSearchStep>,
    name: String,
    role: ContributorRole,
): String? {
    if (steps.isEmpty()) return null
    val said = steps.map { personStepText(it, name, role) }.reduce { first, next -> "$first, then $next" }
    return "Started from $said."
}

private fun personStepText(
    step: PersonSearchStep,
    name: String,
    role: ContributorRole,
): String {
    val verb = if (role == ContributorRole.NARRATOR) "narrates" else "wrote"
    return when (step) {
        is PersonSearchStep.ExistingLink -> "your ${step.source.label} link"
        is PersonSearchStep.ViaYourBooks -> {
            if (step.bookCount == 1) {
                "the book $name $verb in your library"
            } else {
                "the ${step.bookCount} books $name $verb in your library"
            }
        }
        is PersonSearchStep.ByName -> "a search for “${step.query}”"
    }
}

/** "Not a narrator" / "Not an author" — what a Different role row is not, for the role searched. */
internal fun notInRoleText(searched: ContributorRole): String =
    if (searched == ContributorRole.NARRATOR) "Not a narrator" else "Not an author"

/**
 * A person row's second line: "Author · The Martian, Artemis", or "Author · 1 book" when no works are known,
 * and "· Not a narrator" on a Different role row.
 */
internal fun personMetaText(
    candidate: PersonCandidateUi,
    searched: ContributorRole,
): String =
    listOfNotNull(
        roleLabel(candidate.shownRole ?: searched),
        candidate.knownWorks.takeIf { it.isNotEmpty() }?.joinToString(", ")
            ?: candidate.worksCount?.let(::worksCountText),
        notInRoleText(searched).takeIf { candidate.isDifferentRole },
    ).joinToString(" · ")

/** A person row's library line, in the role the sources credit them with. */
internal fun personLibraryText(
    candidate: PersonCandidateUi,
    searched: ContributorRole,
): String =
    if (candidate.noBooksInLibrary) {
        NO_BOOKS_IN_LIBRARY
    } else {
        libraryLineText(candidate.shownRole ?: searched, candidate.libraryCount)
    }

/**
 * A person row's accessible name, saying all of it in order (match.person_row_a11y): "Best match. Andy Weir.
 * Author · The Martian, Artemis. Wrote 3 books in your library. Found in Audible and Hardcover."
 */
internal fun personRowName(
    candidate: PersonCandidateUi,
    searched: ContributorRole,
): String =
    listOfNotNull(
        BEST_MATCH.takeIf { candidate.isBest },
        "Your current link".takeIf { candidate.isCurrentLink },
        candidate.name,
        personMetaText(candidate, searched),
        if (candidate.isDifferentRole) DIFFERENT_ROLE else personLibraryText(candidate, searched),
        candidate.foundIn.takeIf { it.isNotEmpty() }?.let { "Found in ${sourcesText(it)}" },
    ).joinToString(". ", postfix = ".")

/** "Narrator · from Hardcover" under the Review header's name (match.person_header_from). */
internal fun personHeaderFromText(
    candidate: PersonCandidateUi,
    searched: ContributorRole,
): String {
    val role = roleLabel(candidate.shownRole ?: searched)
    return if (candidate.foundIn.isEmpty()) role else "$role · from ${sourcesText(candidate.foundIn)}"
}

/** "No source has a profile for this narrator" (match.no_profiles_*_title). */
internal fun noProfilesTitle(role: ContributorRole): String =
    if (role == ContributorRole.NARRATOR) {
        "No source has a profile for this narrator"
    } else {
        "No source has a profile for this author"
    }

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
internal const val DIFFERENT_ROLE = "Different role"
internal const val NO_PROFILES_BODY = "You can add their photo and biography yourself."
internal const val EDIT_BY_HAND = "Edit by hand"
internal const val REVIEW_RELOADED_PERSON = "This person changed while you were reviewing. Check the changes again."
internal const val NOTHING_CHANGES_UNTIL_APPLY = "Nothing changes until you apply."
