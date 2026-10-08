package com.calypsan.listenup.client.presentation.match

import com.calypsan.listenup.api.dto.ContributorRole
import com.calypsan.listenup.api.dto.match.AppliedChange
import com.calypsan.listenup.api.dto.match.BiographyReview
import com.calypsan.listenup.api.dto.match.ExternalRef
import com.calypsan.listenup.api.dto.match.FieldChoice
import com.calypsan.listenup.api.dto.match.FieldOption
import com.calypsan.listenup.api.dto.match.FieldState
import com.calypsan.listenup.api.dto.match.HandEdit
import com.calypsan.listenup.api.dto.match.ImageChoice
import com.calypsan.listenup.api.dto.match.InLibrary
import com.calypsan.listenup.api.dto.match.MatchReceipt
import com.calypsan.listenup.api.dto.match.MatchTier
import com.calypsan.listenup.api.dto.match.PersonCandidate
import com.calypsan.listenup.api.dto.match.PersonCandidateKey
import com.calypsan.listenup.api.dto.match.PersonFindResult
import com.calypsan.listenup.api.dto.match.PersonMatchReview
import com.calypsan.listenup.api.dto.match.PersonReason
import com.calypsan.listenup.api.dto.match.PersonSearchStep
import com.calypsan.listenup.api.dto.match.PhotoCandidate
import com.calypsan.listenup.api.dto.match.PhotoReview
import com.calypsan.listenup.api.dto.match.RoleCoverage
import com.calypsan.listenup.api.dto.match.SourceStatus
import com.calypsan.listenup.client.domain.model.BookListItem
import com.calypsan.listenup.client.domain.model.Contributor
import com.calypsan.listenup.client.domain.model.ContributorSearchResponse
import com.calypsan.listenup.client.domain.model.ContributorWithBookCount
import com.calypsan.listenup.client.domain.model.RoleWithBookCount
import com.calypsan.listenup.client.domain.repository.BookWithContributorRole
import com.calypsan.listenup.client.domain.repository.ContributorRepository
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.ContributorId
import com.calypsan.listenup.core.FolderId
import com.calypsan.listenup.core.LibraryId
import com.calypsan.listenup.core.Timestamp
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

internal const val PERSON = "person-1"

internal fun personKey(
    provider: String = "hardcover",
    id: String = "hc-ray",
): PersonCandidateKey = PersonCandidateKey(listOf(ExternalRef(provider, id)))

internal fun personCandidate(
    key: PersonCandidateKey = personKey(),
    name: String = "Ray Porter",
    roles: List<ContributorRole> = listOf(ContributorRole.NARRATOR),
    tier: MatchTier = MatchTier.STRONG,
    isBest: Boolean = false,
    libraryCount: Int = 5,
    reasons: List<PersonReason> = emptyList(),
): PersonCandidate =
    PersonCandidate(
        key = key,
        name = name,
        roles = roles,
        photoUrl = "https://img/${key.refs.first().id}.jpg",
        knownWorks = listOf("Project Hail Mary", "Bobiverse"),
        worksCount = 120,
        libraryCount = libraryCount,
        foundIn = listOf(HARDCOVER),
        tier = tier,
        isBest = isBest,
        isCurrentLink = false,
        reasons = reasons,
    )

/** Ray Porter as a narrator: one Strong narrator, one Maybe who is an author at Hardcover. */
internal fun personFindResult(
    role: ContributorRole = ContributorRole.NARRATOR,
    candidates: List<PersonCandidate> =
        listOf(
            personCandidate(isBest = true),
            personCandidate(
                key = personKey(id = "hc-ray-author"),
                roles = listOf(ContributorRole.AUTHOR),
                tier = MatchTier.MAYBE,
                libraryCount = 0,
                reasons =
                    listOf(
                        PersonReason.DifferentRole(listOf(ContributorRole.AUTHOR)),
                        PersonReason.NoBooksInLibrary,
                    ),
            ),
        ),
    coverage: List<RoleCoverage> = listOf(RoleCoverage(AUDIBLE, false), RoleCoverage(HARDCOVER, true)),
    sources: List<SourceStatus> =
        listOf(
            SourceStatus.Unavailable(
                AUDIBLE,
                com.calypsan.listenup.api.dto.match.UnavailableReason.NO_PROFILES_FOR_ROLE,
            ),
            SourceStatus.Answered(HARDCOVER, candidates.size),
        ),
): PersonFindResult =
    PersonFindResult(
        role = role,
        steps = listOf(PersonSearchStep.ViaYourBooks(5), PersonSearchStep.ByName("Ray Porter")),
        inLibrary = InLibrary(5, listOf("Project Hail Mary")),
        coverage = coverage,
        candidates = candidates,
        sources = sources,
    )

internal fun bioOption(
    id: String,
    text: String,
    vararg sources: com.calypsan.listenup.api.dto.match.MetadataSource,
): FieldOption = option(id, text(text), *sources)

/** Ray Porter has no photo and no biography; Hardcover has both. */
internal fun personReview(
    key: PersonCandidateKey = personKey(),
    role: ContributorRole = ContributorRole.NARRATOR,
    currentPhoto: String? = null,
    photoSetByHand: Boolean = false,
    photoOptions: List<PhotoCandidate> = listOf(PhotoCandidate("p-hc", HARDCOVER, "https://img/hc.jpg")),
    photoDefault: ImageChoice = ImageChoice.Candidate("p-hc"),
    biography: BiographyReview? =
        BiographyReview(
            current = null,
            options = listOf(bioOption("b-hc", "Ray Porter is an actor and narrator.", HARDCOVER)),
            defaultChoice = FieldChoice.Option("b-hc"),
            state = FieldState.FILLS_GAP,
            handEdit = null,
        ),
    revision: Long = 7L,
): PersonMatchReview =
    PersonMatchReview(
        candidate = key,
        role = role,
        basedOnRevision = revision,
        photo = PhotoReview(currentPhoto, photoSetByHand, photoOptions, photoDefault),
        biography = biography,
    )

/** A biography you edited by hand: unticked, flagged, two sources. */
internal fun editedBiography(): BiographyReview =
    BiographyReview(
        current = "My own words about Ray.",
        options =
            listOf(
                bioOption("b-au", "Audible's Ray.", AUDIBLE),
                bioOption("b-hc", "Hardcover's Ray.", HARDCOVER),
            ),
        defaultChoice = FieldChoice.KeepCurrent,
        state = FieldState.USER_EDITED,
        handEdit = HandEdit(byUserId = "u-1", byName = "Simon", at = 1_000L),
    )

internal fun personReceipt(id: String = "pr-1"): MatchReceipt =
    MatchReceipt(
        receiptId = id,
        appliedAt = 1L,
        changes = listOf(AppliedChange.Photo(HARDCOVER), AppliedChange.Biography(HARDCOVER)),
        undoable = true,
    )

internal fun contributor(name: String = "Ray Porter"): Contributor =
    Contributor(id = ContributorId(PERSON), name = name, imagePath = null)

internal fun libraryBook(
    id: String,
    title: String,
): BookWithContributorRole =
    BookWithContributorRole(
        book =
            BookListItem(
                id = BookId(id),
                libraryId = LibraryId("lib-1"),
                folderId = FolderId("folder-1"),
                title = title,
                authors = emptyList(),
                narrators = emptyList(),
                duration = 1L,
                coverPath = "covers/$id.jpg",
                coverHash = "h-$id",
                addedAt = Timestamp(0),
                updatedAt = Timestamp(0),
            ),
        creditedAs = null,
    )

/** [ContributorRepository] with in-memory state for the reads person matching makes. */
internal class FakeContributorRepository : ContributorRepository {
    val contributor = MutableStateFlow<Contributor?>(contributor())
    val roles = MutableStateFlow(listOf(RoleWithBookCount("narrator", 5), RoleWithBookCount("author", 1)))
    val booksByRole =
        MutableStateFlow(
            mapOf(
                "narrator" to (1..5).map { libraryBook("n-$it", "Narrated $it") },
                "author" to listOf(libraryBook("a-1", "Written 1")),
            ),
        )

    override fun observeById(id: String): Flow<Contributor?> = contributor

    override fun observeRolesWithCountForContributor(contributorId: String): Flow<List<RoleWithBookCount>> = roles

    override fun observeBooksForContributorRole(
        contributorId: String,
        role: String,
    ): Flow<List<BookWithContributorRole>> = booksByRole.map { it[role].orEmpty() }

    override fun observeAll(): Flow<List<Contributor>> = error("unused")

    override suspend fun getById(id: String): Contributor? = contributor.value

    override fun observeByBookId(bookId: String): Flow<List<Contributor>> = error("unused")

    override suspend fun getByBookId(bookId: String): List<Contributor> = error("unused")

    override suspend fun getBookIdsForContributor(contributorId: String): List<String> = error("unused")

    override fun observeBookIdsForContributor(contributorId: String): Flow<List<String>> = error("unused")

    override fun observeContributorsByRole(role: String): Flow<List<ContributorWithBookCount>> = error("unused")

    override suspend fun searchContributors(
        query: String,
        limit: Int,
    ): ContributorSearchResponse = error("unused")

    override suspend fun upsertContributor(contributor: Contributor) = error("unused")
}
