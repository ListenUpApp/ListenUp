package com.calypsan.listenup.web.features.match

import com.calypsan.listenup.api.dto.ContributorRole
import com.calypsan.listenup.api.dto.match.AppliedChange
import com.calypsan.listenup.api.dto.match.ExternalRef
import com.calypsan.listenup.api.dto.match.FieldChoice
import com.calypsan.listenup.api.dto.match.FieldState
import com.calypsan.listenup.api.dto.match.FieldValue
import com.calypsan.listenup.api.dto.match.HandEdit
import com.calypsan.listenup.api.dto.match.ImageChoice
import com.calypsan.listenup.api.dto.match.LibraryCredit
import com.calypsan.listenup.api.dto.match.MatchTier
import com.calypsan.listenup.api.dto.match.MetadataSource
import com.calypsan.listenup.api.dto.match.PersonCandidateKey
import com.calypsan.listenup.api.dto.match.PersonSearchStep
import com.calypsan.listenup.api.error.AppError
import com.calypsan.listenup.client.presentation.match.BiographyUi
import com.calypsan.listenup.client.presentation.match.FieldOptionUi
import com.calypsan.listenup.client.presentation.match.FindFailure
import com.calypsan.listenup.client.presentation.match.InLibraryUi
import com.calypsan.listenup.client.presentation.match.LibraryCoverUi
import com.calypsan.listenup.client.presentation.match.MatchReceiptUi
import com.calypsan.listenup.client.presentation.match.PartialFailure
import com.calypsan.listenup.client.presentation.match.PersonApplySummary
import com.calypsan.listenup.client.presentation.match.PersonCandidateUi
import com.calypsan.listenup.client.presentation.match.PersonFindUiState
import com.calypsan.listenup.client.presentation.match.PersonHeaderUi
import com.calypsan.listenup.client.presentation.match.PersonReviewUiState
import com.calypsan.listenup.client.presentation.match.PhotoOptionUi
import com.calypsan.listenup.client.presentation.match.PhotoUi

/*
 * Person Match details as the shared ViewModel would hand it to web (W-06, W-07), beside MatchFixtures.kt's
 * book values and reusing its sources. Provider labels are test data, not UI code.
 */

internal fun personHeader(name: String = "Andy Weir"): PersonHeaderUi = PersonHeaderUi(name = name, imagePath = null)

internal fun inLibrary(
    titles: List<String> = listOf("Project Hail Mary", "The Martian", "Artemis"),
    bookCount: Int = titles.size,
    credits: List<LibraryCredit> = listOf(LibraryCredit(ContributorRole.AUTHOR, bookCount)),
): InLibraryUi =
    InLibraryUi(
        credits = credits,
        bookCount = bookCount,
        titles = titles,
        covers =
            titles.mapIndexed {
                index,
                title,
                ->
                LibraryCoverUi("b-$index", title, "covers/$index.jpg", "h$index")
            },
    )

internal fun person(
    id: String = "P1",
    name: String = "Andy Weir",
    shownRole: ContributorRole? = ContributorRole.AUTHOR,
    knownWorks: List<String> = listOf("The Martian", "Artemis"),
    worksCount: Int? = null,
    libraryCount: Int = 3,
    foundIn: List<MetadataSource> = listOf(AUDIBLE, HARDCOVER),
    tier: MatchTier = MatchTier.STRONG,
    isBest: Boolean = false,
    isCurrentLink: Boolean = false,
    libraryCredits: List<LibraryCredit> =
        if (libraryCount > 0) listOf(LibraryCredit(ContributorRole.AUTHOR, libraryCount)) else emptyList(),
    noBooksInLibrary: Boolean = false,
    photoUrl: String? = "https://example.invalid/$id.jpg",
): PersonCandidateUi =
    PersonCandidateUi(
        id = "audible:$id",
        key = PersonCandidateKey(listOf(ExternalRef("audible", id))),
        name = name,
        photoUrl = photoUrl,
        shownRole = shownRole,
        knownWorks = knownWorks,
        worksCount = worksCount,
        libraryCount = libraryCount,
        foundIn = foundIn,
        tier = tier,
        isBest = isBest,
        isCurrentLink = isCurrentLink,
        libraryCredits = libraryCredits,
        noBooksInLibrary = noBooksInLibrary,
    )

/** W-06: Andy Weir, who wrote three of your books — one Strong match, two Maybes. */
internal fun andyResults(
    strong: List<PersonCandidateUi> = listOf(person(isBest = true)),
    maybe: List<PersonCandidateUi> =
        listOf(
            person(
                id = "P2",
                knownWorks = listOf("Local history"),
                libraryCount = 0,
                noBooksInLibrary = true,
                foundIn = listOf(HARDCOVER),
                tier = MatchTier.MAYBE,
                photoUrl = null,
            ),
            person(
                id = "P3",
                name = "Andrew Weir",
                knownWorks = emptyList(),
                worksCount = 1,
                libraryCount = 0,
                noBooksInLibrary = true,
                foundIn = listOf(AUDIBLE),
                tier = MatchTier.MAYBE,
                photoUrl = null,
            ),
        ),
    pickedKey: PersonCandidateKey? = null,
    partialFailure: PartialFailure? = null,
): PersonFindUiState.Results =
    PersonFindUiState.Results(
        header = personHeader(),
        inLibrary = inLibrary(),
        query = "Andy Weir",
        steps = listOf(PersonSearchStep.ViaYourBooks(3)),
        strong = strong,
        maybe = maybe,
        partialFailure = partialFailure,
        pickedKey = pickedKey,
    )

internal val RAY =
    person(
        id = "R1",
        name = "Ray Porter",
        shownRole = ContributorRole.NARRATOR,
        knownWorks = listOf("Project Hail Mary", "Bobiverse"),
        libraryCount = 5,
        libraryCredits =
            listOf(
                LibraryCredit(ContributorRole.NARRATOR, 4),
                LibraryCredit(ContributorRole.TRANSLATOR, 1),
            ),
        foundIn = listOf(HARDCOVER),
        isBest = true,
    )

internal val RAY_THE_AUTHOR =
    person(
        id = "R2",
        name = "Ray Porter",
        shownRole = ContributorRole.AUTHOR,
        knownWorks = emptyList(),
        worksCount = 1,
        libraryCount = 0,
        foundIn = listOf(HARDCOVER),
        tier = MatchTier.MAYBE,
        noBooksInLibrary = true,
        photoUrl = null,
    )

/** The strip for Ray Porter: narrated five of your books, translated one. */
internal val RAY_LIBRARY: List<LibraryCredit> =
    listOf(LibraryCredit(ContributorRole.NARRATOR, 5), LibraryCredit(ContributorRole.TRANSLATOR, 1))

/** W-07: Ray Porter, who narrated five of your books and translated one — one Strong match, one Maybe. */
internal fun rayResults(): PersonFindUiState.Results =
    PersonFindUiState.Results(
        header = personHeader("Ray Porter"),
        inLibrary = inLibrary(listOf("Project Hail Mary", "Bobiverse"), bookCount = 6, credits = RAY_LIBRARY),
        query = "Ray Porter",
        steps = listOf(PersonSearchStep.ViaYourBooks(5)),
        strong = listOf(RAY),
        maybe = listOf(RAY_THE_AUTHOR),
        partialFailure = null,
        pickedKey = null,
    )

internal fun personSearching(previous: PersonFindUiState.Results? = null): PersonFindUiState.Searching =
    PersonFindUiState.Searching(personHeader(), inLibrary(), "Andy Weir", previous)

internal fun noProfiles(): PersonFindUiState.NoProfiles =
    PersonFindUiState.NoProfiles(personHeader("Ray Porter"), inLibrary(credits = RAY_LIBRARY), "Ray Porter")

internal fun personFailed(failure: FindFailure): PersonFindUiState.Failed =
    PersonFindUiState.Failed(personHeader(), inLibrary(), "Andy Weir", failure)

internal fun photo(
    currentPath: String? = null,
    setByHand: Boolean = false,
    state: FieldState = FieldState.FILLS_GAP,
    choice: ImageChoice = ImageChoice.Candidate("ph-a"),
): PhotoUi {
    val options =
        listOf(
            PhotoOptionUi("ph-a", AUDIBLE, "https://example.invalid/a.jpg"),
            PhotoOptionUi("ph-h", HARDCOVER, "https://example.invalid/h.jpg"),
        )
    return PhotoUi(
        currentPath = currentPath,
        setByHand = setByHand,
        state = state,
        options = options,
        choice = choice,
        proposed = options.firstOrNull { it.optionId == (choice as? ImageChoice.Candidate)?.optionId } ?: options[0],
    )
}

internal const val BIO_A =
    "Andy Weir built a career as a software engineer before publishing The Martian, which became a bestseller " +
        "and a film. He lives in California, where he writes science fiction full of careful arithmetic and " +
        "engineers who solve one problem at a time."

internal fun biography(
    state: FieldState = FieldState.CHANGES,
    current: String? = "A writer.",
    choice: FieldChoice = FieldChoice.Option("bio-a"),
    handEdit: HandEdit? = null,
): BiographyUi {
    val options =
        listOf(
            FieldOptionUi("bio-a", FieldValue.Text(BIO_A), listOf(AUDIBLE)),
            FieldOptionUi("bio-h", FieldValue.Text("Andy Weir writes science fiction."), listOf(HARDCOVER)),
        )
    return BiographyUi(
        state = state,
        current = current,
        options = options,
        choice = choice,
        proposed = options.firstOrNull { it.optionId == (choice as? FieldChoice.Option)?.optionId } ?: options[0],
        handEdit = handEdit,
    )
}

internal fun personReady(
    candidate: PersonCandidateUi = person(isBest = true),
    photo: PhotoUi? = photo(),
    biography: BiographyUi? = biography(),
    applying: Boolean = false,
    applyError: AppError? = null,
): PersonReviewUiState.Ready =
    PersonReviewUiState.Ready(
        candidate = candidate,
        photo = photo,
        biography = biography,
        applyBar =
            PersonApplySummary(
                photo = photo?.isTicked == true,
                biography = biography?.isTicked == true,
                sources = listOfNotNull(photo?.chosen?.source),
            ),
        applying = applying,
        applyError = applyError,
    )

internal fun personReceipt(
    photo: MetadataSource? = HARDCOVER,
    biography: MetadataSource? = HARDCOVER,
): MatchReceiptUi =
    MatchReceiptUi(
        receiptId = "r-p",
        fieldCount = 0,
        coverSource = null,
        chapterNameCount = 0,
        photoSource = photo,
        biographySource = biography,
        changes = listOfNotNull(photo?.let(AppliedChange::Photo), biography?.let(AppliedChange::Biography)),
        undoable = true,
    )
