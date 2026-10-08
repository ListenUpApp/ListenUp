package com.calypsan.listenup.client.features.match

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
import com.calypsan.listenup.api.dto.match.PersonCandidateKey
import com.calypsan.listenup.api.dto.match.PersonSearchStep
import com.calypsan.listenup.client.features.match.MatchFixtures.ATLAS
import com.calypsan.listenup.client.features.match.MatchFixtures.BEACON
import com.calypsan.listenup.client.features.match.MatchFixtures.LOCAL_COVER
import com.calypsan.listenup.client.presentation.match.BiographyUi
import com.calypsan.listenup.client.presentation.match.FieldOptionUi
import com.calypsan.listenup.client.presentation.match.FindFailure
import com.calypsan.listenup.client.presentation.match.InLibraryUi
import com.calypsan.listenup.client.presentation.match.LibraryCoverUi
import com.calypsan.listenup.client.presentation.match.MatchReceiptUi
import com.calypsan.listenup.client.presentation.match.PersonApplySummary
import com.calypsan.listenup.client.presentation.match.PersonCandidateUi
import com.calypsan.listenup.client.presentation.match.PersonFindUiState
import com.calypsan.listenup.client.presentation.match.PersonHeaderUi
import com.calypsan.listenup.client.presentation.match.PersonReviewUiState
import com.calypsan.listenup.client.presentation.match.PhotoOptionUi
import com.calypsan.listenup.client.presentation.match.PhotoUi

/**
 * Fixed person Match details state for content tests: Andy Weir's Find, Ray Porter's — who narrated five books here
 * and translated one — and Ray's Review. Source labels are invented — the UI treats them as opaque.
 */
internal object PersonMatchFixtures {
    const val CONTRIBUTOR_ID = "contributor-1"

    private fun key(id: String) = PersonCandidateKey(refs = listOf(ExternalRef(provider = "beacon", id = id)))

    private fun cover(
        id: String,
        title: String,
    ) = LibraryCoverUi(bookId = id, title = title, coverPath = LOCAL_COVER, coverHash = null)

    // Author: Andy Weir.

    val andy = PersonHeaderUi(name = "Andy Weir", imagePath = null)

    val andyLibrary =
        InLibraryUi(
            credits = listOf(LibraryCredit(ContributorRole.AUTHOR, 3)),
            bookCount = 3,
            titles = listOf("Project Hail Mary", "The Martian", "Artemis"),
            covers = listOf(cover("b1", "Project Hail Mary"), cover("b2", "The Martian"), cover("b3", "Artemis")),
        )

    private fun person(
        id: String,
        name: String,
        tier: MatchTier,
        shownRole: ContributorRole? = ContributorRole.AUTHOR,
        knownWorks: List<String> = emptyList(),
        worksCount: Int? = null,
        libraryCount: Int = 0,
        foundIn: List<com.calypsan.listenup.api.dto.match.MetadataSource> = listOf(BEACON),
        isBest: Boolean = false,
        libraryCredits: List<LibraryCredit> = emptyList(),
        noBooksInLibrary: Boolean = false,
    ) = PersonCandidateUi(
        id = "beacon:$id",
        key = key(id),
        name = name,
        photoUrl = null,
        shownRole = shownRole,
        knownWorks = knownWorks,
        worksCount = worksCount,
        libraryCount = libraryCount,
        foundIn = foundIn,
        tier = tier,
        isBest = isBest,
        isCurrentLink = false,
        libraryCredits = libraryCredits,
        noBooksInLibrary = noBooksInLibrary,
    )

    val andyWeir =
        person(
            id = "andy",
            name = "Andy Weir",
            tier = MatchTier.STRONG,
            knownWorks = listOf("The Martian", "Artemis"),
            libraryCount = 3,
            foundIn = listOf(ATLAS, BEACON),
            isBest = true,
            libraryCredits = listOf(LibraryCredit(ContributorRole.AUTHOR, 3)),
        )

    val localHistorian =
        person(
            id = "local",
            name = "Andy Weir",
            tier = MatchTier.MAYBE,
            knownWorks = listOf("Local history"),
            noBooksInLibrary = true,
        )

    val andrewWeir =
        person(id = "andrew", name = "Andrew Weir", tier = MatchTier.MAYBE, worksCount = 1, foundIn = listOf(ATLAS))

    val andyResults =
        PersonFindUiState.Results(
            header = andy,
            inLibrary = andyLibrary,
            query = "Andy Weir",
            steps = listOf(PersonSearchStep.ViaYourBooks(bookCount = 3)),
            strong = listOf(andyWeir),
            maybe = listOf(localHistorian, andrewWeir),
            partialFailure = null,
            pickedKey = null,
        )

    // Ray Porter: narrated five books here, and translated one.

    val ray = PersonHeaderUi(name = "Ray Porter", imagePath = null)

    val rayLibrary =
        InLibraryUi(
            credits = listOf(LibraryCredit(ContributorRole.NARRATOR, 5), LibraryCredit(ContributorRole.TRANSLATOR, 1)),
            bookCount = 6,
            titles = listOf("Project Hail Mary", "We Are Legion", "For We Are Many"),
            covers = listOf(cover("b1", "Project Hail Mary")),
        )

    val rayPorter =
        person(
            id = "ray",
            name = "Ray Porter",
            tier = MatchTier.STRONG,
            shownRole = ContributorRole.NARRATOR,
            knownWorks = listOf("Project Hail Mary", "Bobiverse"),
            libraryCount = 5,
            isBest = true,
            libraryCredits =
                listOf(
                    LibraryCredit(ContributorRole.NARRATOR, 4),
                    LibraryCredit(ContributorRole.TRANSLATOR, 1),
                ),
        )

    val rayTheAuthor =
        person(
            id = "ray-author",
            name = "Ray Porter",
            tier = MatchTier.MAYBE,
            shownRole = ContributorRole.AUTHOR,
            worksCount = 1,
            noBooksInLibrary = true,
        )

    val rayResults =
        PersonFindUiState.Results(
            header = ray,
            inLibrary = rayLibrary,
            query = "Ray Porter",
            steps = listOf(PersonSearchStep.ViaYourBooks(bookCount = 5)),
            strong = listOf(rayPorter),
            maybe = listOf(rayTheAuthor),
            partialFailure = null,
            pickedKey = null,
        )

    val noProfiles =
        PersonFindUiState.NoProfiles(
            header = ray,
            inLibrary = rayLibrary,
            query = "Ray Porter",
        )

    val timedOut =
        PersonFindUiState.Failed(
            header = ray,
            inLibrary = rayLibrary,
            query = "Ray Porter",
            failure = FindFailure.TimedOut(BEACON),
        )

    val searching =
        PersonFindUiState.Searching(
            header = ray,
            inLibrary = rayLibrary,
            query = "Ray Porter",
            previous = null,
        )

    // Review: Ray Porter has no photo and no biography yet.

    val photoOption = PhotoOptionUi(optionId = "photo-beacon", source = BEACON, url = "https://example.invalid/ray.jpg")

    val photo =
        PhotoUi(
            currentPath = null,
            setByHand = false,
            state = FieldState.FILLS_GAP,
            options = listOf(photoOption),
            choice = ImageChoice.Candidate(photoOption.optionId),
            proposed = photoOption,
        )

    const val PROPOSED_BIO = "Ray Porter is an actor and audiobook narrator known for science fiction."

    val bioOption =
        FieldOptionUi(optionId = "bio-beacon", value = FieldValue.Text(PROPOSED_BIO), sources = listOf(BEACON))
    val bioOptionAtlas =
        FieldOptionUi(optionId = "bio-atlas", value = FieldValue.Text("A narrator."), sources = listOf(ATLAS))

    val biography =
        BiographyUi(
            state = FieldState.FILLS_GAP,
            current = null,
            options = listOf(bioOption),
            choice = FieldChoice.Option(bioOption.optionId),
            proposed = bioOption,
            handEdit = null,
        )

    val ready =
        PersonReviewUiState.Ready(
            candidate = rayPorter,
            photo = photo,
            biography = biography,
            applyBar = PersonApplySummary(photo = true, biography = true, sources = listOf(BEACON)),
            applying = false,
            applyError = null,
        )

    /** Ray's biography was written by hand, and two sources offer one: unticked, flagged, with a source switch. */
    val handEditedBiography =
        biography.copy(
            state = FieldState.USER_EDITED,
            current = "Ray narrates.",
            options = listOf(bioOption, bioOptionAtlas),
            choice = FieldChoice.KeepCurrent,
            handEdit = HandEdit(byUserId = MatchFixtures.VIEWER_ID, byName = "Simon", at = null),
        )

    val receipt =
        MatchReceiptUi(
            receiptId = "receipt-ray",
            fieldCount = 0,
            coverSource = null,
            chapterNameCount = 0,
            photoSource = BEACON,
            biographySource = BEACON,
            changes = listOf(AppliedChange.Photo(BEACON), AppliedChange.Biography(BEACON)),
            undoable = true,
        )
}

/** Records every action the person screens ask for, so a content test can assert what a tap did. */
internal class RecordingPersonMatchActions : PersonMatchActions {
    val calls = mutableListOf<String>()
    val twoPaneReports = mutableListOf<Boolean>()

    override fun search(query: String) {
        calls += "search:$query"
    }

    override fun retry() {
        calls += "retry"
    }

    override fun pick(key: PersonCandidateKey) {
        calls += "pick:${key.refs.single().id}"
    }

    override fun backToResults() {
        calls += "backToResults"
    }

    override fun useTwoPane(enabled: Boolean) {
        twoPaneReports += enabled
    }

    override fun choosePhoto(choice: ImageChoice) {
        calls += "photo:$choice"
    }

    override fun setBiographyTicked(ticked: Boolean) {
        calls += "bio:$ticked"
    }

    override fun chooseBiographySource(choice: FieldChoice) {
        calls += "bioSource:$choice"
    }

    override fun apply() {
        calls += "apply"
    }
}
