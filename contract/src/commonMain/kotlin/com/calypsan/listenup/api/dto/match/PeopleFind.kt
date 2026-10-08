package com.calypsan.listenup.api.dto.match

import com.calypsan.listenup.api.dto.ContributorRole
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * What a people Find asks: [query] when the person typed one, replacing the contributor's own name. A Find looks
 * for the person in every role a source knows — a role on one book doesn't say who someone is. [role] is what a
 * client from before that change still sends (As author | As narrator); the server ignores it and echoes it back.
 */
@Serializable
@SerialName("PersonFindRequest")
data class PersonFindRequest(
    @SerialName("role") val role: ContributorRole? = null,
    @SerialName("query") val query: String? = null,
)

/** One step a people Find took, in order, so clients can say what it started from. */
@Serializable
sealed interface PersonSearchStep {
    /** It started from the person's existing link at [source]. */
    @Serializable
    @SerialName("PersonSearchStep.ExistingLink")
    data class ExistingLink(
        @SerialName("source") val source: MetadataSource,
    ) : PersonSearchStep

    /** It read the credits of [bookCount] of the books they're credited on in your library. */
    @Serializable
    @SerialName("PersonSearchStep.ViaYourBooks")
    data class ViaYourBooks(
        @SerialName("bookCount") val bookCount: Int,
    ) : PersonSearchStep

    /** It searched by [query]: their name, or what the person typed. */
    @Serializable
    @SerialName("PersonSearchStep.ByName")
    data class ByName(
        @SerialName("query") val query: String,
    ) : PersonSearchStep
}

/** How many of this library's books credit the person, in any role, and up to three of their titles. */
@Serializable
@SerialName("InLibrary")
data class InLibrary(
    @SerialName("bookCount") val bookCount: Int,
    @SerialName("titles") val titles: List<String>,
)

/**
 * Whether [source] was asked. Every people source is asked now, so [hasProfiles] is always true; the list stays
 * because clients from before role-free matching require it.
 */
@Serializable
@SerialName("RoleCoverage")
data class RoleCoverage(
    @SerialName("source") val source: MetadataSource,
    @SerialName("hasProfiles") val hasProfiles: Boolean,
)

/** A person candidate's identity: their ref at every source that found them. Review and Apply take it back. */
@Serializable
@SerialName("PersonCandidateKey")
data class PersonCandidateKey(
    @SerialName("refs") val refs: List<ExternalRef>,
) {
    init {
        require(refs.isNotEmpty()) { "A candidate key needs at least one ref" }
    }
}

/** Why a person candidate ranks where it does. Clients phrase them. */
@Serializable
sealed interface PersonReason {
    /** The source credits them on none of the books they're credited on in your library. */
    @Serializable
    @SerialName("PersonReason.NoBooksInLibrary")
    data object NoBooksInLibrary : PersonReason
}

/** "Narrated 3 of your books": how many of this library's books credit the person in [role]. */
@Serializable
@SerialName("LibraryCredit")
data class LibraryCredit(
    @SerialName("role") val role: ContributorRole,
    @SerialName("bookCount") val bookCount: Int,
)

/**
 * One person Find found, merged across the sources that agree it is the same person. [roles] are the roles the
 * sources credit them in; [knownWorks] up to two titles and [worksCount] how many books a source credits them on;
 * [libraryCount] how many of this library's books the sources credit to this person, in any role, and
 * [libraryCredits] what the contributor did on those books here — most first. [key] is what Review takes back.
 */
@Serializable
@SerialName("PersonCandidate")
data class PersonCandidate(
    @SerialName("key") val key: PersonCandidateKey,
    @SerialName("name") val name: String,
    @SerialName("roles") val roles: List<ContributorRole>,
    @SerialName("photoUrl") val photoUrl: String?,
    @SerialName("knownWorks") val knownWorks: List<String>,
    @SerialName("worksCount") val worksCount: Int?,
    @SerialName("libraryCount") val libraryCount: Int,
    @SerialName("foundIn") val foundIn: List<MetadataSource>,
    @SerialName("tier") val tier: MatchTier,
    @SerialName("isBest") val isBest: Boolean,
    @SerialName("isCurrentLink") val isCurrentLink: Boolean,
    @SerialName("reasons") val reasons: List<PersonReason>,
    @SerialName("libraryCredits") val libraryCredits: List<LibraryCredit> = emptyList(),
)

/**
 * A people Find's answer: the [steps] it took, the person's books [inLibrary], the [candidates] best first, and how
 * every source fared. No candidates with every source answering empty is "No source has a profile for this
 * person". [role] and [coverage] are for clients from before role-free matching, which require them: [role]
 * echoes the role such a client sent, so a new client — which sends none — never sees one.
 */
@Serializable
@SerialName("PersonFindResult")
data class PersonFindResult(
    @SerialName("steps") val steps: List<PersonSearchStep>,
    @SerialName("inLibrary") val inLibrary: InLibrary,
    @SerialName("coverage") val coverage: List<RoleCoverage>,
    @SerialName("candidates") val candidates: List<PersonCandidate>,
    @SerialName("sources") val sources: List<SourceStatus>,
    @SerialName("role") val role: ContributorRole? = null,
)
