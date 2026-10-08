package com.calypsan.listenup.client.presentation.match

import com.calypsan.listenup.api.dto.ContributorRole
import com.calypsan.listenup.api.dto.match.FieldChoice
import com.calypsan.listenup.api.dto.match.FieldState
import com.calypsan.listenup.api.dto.match.HandEdit
import com.calypsan.listenup.api.dto.match.ImageChoice
import com.calypsan.listenup.api.dto.match.LibraryCredit
import com.calypsan.listenup.api.dto.match.MatchReceipt
import com.calypsan.listenup.api.dto.match.MatchTier
import com.calypsan.listenup.api.dto.match.MetadataSource
import com.calypsan.listenup.api.dto.match.PersonCandidate
import com.calypsan.listenup.api.dto.match.PersonCandidateKey
import com.calypsan.listenup.api.dto.match.PersonReason
import com.calypsan.listenup.api.dto.match.PersonSearchStep
import com.calypsan.listenup.api.error.AppError

/** How many of the person's books the Your-library strip names and shows. */
internal const val LIBRARY_STRIP_BOOKS: Int = 3

/** The person being matched, as this device already knows them — read from Room, so Find is never blank. */
data class PersonHeaderUi(
    val name: String,
    val imagePath: String?,
)

/** One of the person's books in the strip: enough to draw its cover. */
data class LibraryCoverUi(
    val bookId: String,
    val title: String,
    val coverPath: String?,
    val coverHash: String?,
)

/**
 * "Narrated 5 of your books · Wrote 1: Project Hail Mary, The Martian, Artemis" — what the person did in this
 * library, every role, read from Room. [credits] are most books first; [bookCount] counts each book once however
 * many roles they hold on it; [titles] and [covers] hold at most [LIBRARY_STRIP_BOOKS].
 */
data class InLibraryUi(
    val credits: List<LibraryCredit>,
    val bookCount: Int,
    val titles: List<String>,
    val covers: List<LibraryCoverUi>,
)

/**
 * One people-Find candidate as every platform renders it. [id] is a stable key for lists and test tags.
 * [shownRole] is the role the row names: the source role they hold on the most of your books, else the source's
 * first. [libraryCredits] are the row's evidence — "Narrated 3 of your books · Translated 1" — most first.
 */
data class PersonCandidateUi(
    val id: String,
    val key: PersonCandidateKey,
    val name: String,
    val photoUrl: String?,
    val shownRole: ContributorRole?,
    val knownWorks: List<String>,
    val worksCount: Int?,
    val libraryCount: Int,
    val foundIn: List<MetadataSource>,
    val tier: MatchTier,
    val isBest: Boolean,
    val isCurrentLink: Boolean,
    val libraryCredits: List<LibraryCredit>,
    val noBooksInLibrary: Boolean,
)

/**
 * The Find step of person Match details. Every state carries what Room already knows. There is no role: Find looks
 * for the person in every role any source knows.
 */
sealed interface PersonFindUiState {
    /** The person, or null for the instant before Room answers. */
    val header: PersonHeaderUi?

    /** What the person did in this library, or null before Room answers. */
    val inLibrary: InLibraryUi?

    /** The search box's text. */
    val query: String

    /** A search is running; [previous] keeps the last results on screen. */
    data class Searching(
        override val header: PersonHeaderUi?,
        override val inLibrary: InLibraryUi?,
        override val query: String,
        val previous: Results?,
    ) : PersonFindUiState

    /** People to choose from, Strong first. [pickedKey] is the row last opened in Review. */
    data class Results(
        override val header: PersonHeaderUi?,
        override val inLibrary: InLibraryUi?,
        override val query: String,
        val steps: List<PersonSearchStep>,
        val strong: List<PersonCandidateUi>,
        val maybe: List<PersonCandidateUi>,
        val partialFailure: PartialFailure?,
        val pickedKey: PersonCandidateKey?,
    ) : PersonFindUiState {
        /** Every candidate, Strong then Maybe. */
        val all: List<PersonCandidateUi> get() = strong + maybe
    }

    /** "No source has a profile for this person": every source answered empty. The way forward is Edit by hand. */
    data class NoProfiles(
        override val header: PersonHeaderUi?,
        override val inLibrary: InLibraryUi?,
        override val query: String,
    ) : PersonFindUiState

    /** Nothing to show because a source or the server failed, and why. */
    data class Failed(
        override val header: PersonHeaderUi?,
        override val inLibrary: InLibraryUi?,
        override val query: String,
        val failure: FindFailure,
    ) : PersonFindUiState
}

/** One source's photo, a tile in the photo choice. */
data class PhotoOptionUi(
    val optionId: String,
    val source: MetadataSource,
    val url: String,
)

/**
 * The photo decision: Keep current, or one source's photo. [state] labels the section — fills a gap when the
 * person has no photo, you edited this when it was set by hand, otherwise changes. [proposed] is the chosen tile,
 * or the one re-ticking restores.
 */
data class PhotoUi(
    val currentPath: String?,
    val setByHand: Boolean,
    val state: FieldState,
    val options: List<PhotoOptionUi>,
    val choice: ImageChoice,
    val proposed: PhotoOptionUi,
) {
    /** Whether Apply writes a photo. */
    val isTicked: Boolean get() = choice is ImageChoice.Candidate

    /** The tile Apply writes, or null for Keep current. */
    val chosen: PhotoOptionUi? get() = if (isTicked) proposed else null
}

/**
 * The biography decision: Yours → Proposed with the same state rules as a book field. [choice] is the tick and
 * the source switch as one value — unticked is [FieldChoice.KeepCurrent].
 */
data class BiographyUi(
    val state: FieldState,
    val current: String?,
    val options: List<FieldOptionUi>,
    val choice: FieldChoice,
    val proposed: FieldOptionUi,
    val handEdit: HandEdit?,
) {
    /** Whether Apply writes the biography. */
    val isTicked: Boolean get() = choice is FieldChoice.Option

    /** Whether a "Keep yours" segment belongs in the source switch: only when there is something to keep. */
    val canKeepYours: Boolean get() = current != null
}

/** The sticky Apply bar: "Photo · biography". */
data class PersonApplySummary(
    val photo: Boolean,
    val biography: Boolean,
    /** The sources of what Apply writes, without repeats — "from Hardcover". */
    val sources: List<MetadataSource>,
) {
    /** Whether Apply would change anything. */
    val canApply: Boolean get() = photo || biography
}

/** The Review step of person Match details. */
sealed interface PersonReviewUiState {
    /** Nothing picked yet: phones show Find alone, two panes show a placeholder. */
    data object NoneChosen : PersonReviewUiState

    /** Loading the Review for [candidate]. */
    data class Loading(
        val candidate: PersonCandidateUi,
    ) : PersonReviewUiState

    /**
     * The photo and the biography, each its own decision. [photo] is null when no source has a photo, [biography]
     * null when none has one.
     */
    data class Ready(
        val candidate: PersonCandidateUi,
        val photo: PhotoUi?,
        val biography: BiographyUi?,
        val applyBar: PersonApplySummary,
        val applying: Boolean,
        val applyError: AppError?,
    ) : PersonReviewUiState

    /** The Review couldn't load. */
    data class Failed(
        val candidate: PersonCandidateUi,
        val error: AppError,
    ) : PersonReviewUiState
}

/** One-shot outcomes of person Match details. */
sealed interface PersonMatchEvent {
    /** Apply committed; every layout returns to the contributor page, which shows the receipt. */
    data class Applied(
        val receipt: MatchReceipt,
    ) : PersonMatchEvent

    /** Apply found the person changed; Review reloaded with the choices that survived. */
    data object ReviewReloaded : PersonMatchEvent
}

internal fun PersonCandidate.toUi(): PersonCandidateUi =
    PersonCandidateUi(
        id = key.stableId(),
        key = key,
        name = name,
        photoUrl = photoUrl,
        shownRole = libraryCredits.firstOrNull { it.role in roles }?.role ?: roles.firstOrNull(),
        knownWorks = knownWorks,
        worksCount = worksCount,
        libraryCount = libraryCount,
        foundIn = foundIn,
        tier = tier,
        isBest = isBest,
        isCurrentLink = isCurrentLink,
        libraryCredits = libraryCredits,
        noBooksInLibrary = reasons.any { it is PersonReason.NoBooksInLibrary },
    )

/** A key's refs as one stable string, so the same person has the same id on every Find. */
internal fun PersonCandidateKey.stableId(): String =
    refs
        .map { "${it.provider}:${it.id}:${it.region.orEmpty()}" }
        .sorted()
        .joinToString("|")
