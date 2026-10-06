package com.calypsan.listenup.api.dto.match

import com.calypsan.listenup.api.dto.ContributorRole
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * What a people Find asks: the [role] to find the person in (authors and narrators have separate searches and
 * sources), and [query] when the person typed one, replacing the contributor's own name.
 */
@Serializable
@SerialName("PersonFindRequest")
data class PersonFindRequest(
    @SerialName("role") val role: ContributorRole,
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

/** How many of this library's books credit the person in the role searched, and up to three of their titles. */
@Serializable
@SerialName("InLibrary")
data class InLibrary(
    @SerialName("bookCount") val bookCount: Int,
    @SerialName("titles") val titles: List<String>,
)

/** Whether [source] has profiles for the role searched — so clients can say which source a search used. */
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
    /** The source doesn't credit them in the role searched; it credits them as [theirRoles]. */
    @Serializable
    @SerialName("PersonReason.DifferentRole")
    data class DifferentRole(
        @SerialName("theirRoles") val theirRoles: List<ContributorRole>,
    ) : PersonReason

    /** The source credits them on none of the books they're credited on in your library. */
    @Serializable
    @SerialName("PersonReason.NoBooksInLibrary")
    data object NoBooksInLibrary : PersonReason
}

/**
 * One person Find found, merged across the sources that agree it is the same person. [roles] are the roles the
 * sources credit them in; [knownWorks] up to two titles and [worksCount] how many books a source credits them on;
 * [libraryCount] how many of this library's books crediting the contributor in the role the sources credit to
 * this person. [key] is what Review takes back.
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
)

/**
 * A people Find's answer for [role]: the [steps] it took, the person's books [inLibrary], each source's
 * [coverage] of the role, the [candidates] best first, and how every source fared. No candidates, with no
 * source covering the role or every covering source empty, is "No source has a profile for this narrator".
 */
@Serializable
@SerialName("PersonFindResult")
data class PersonFindResult(
    @SerialName("role") val role: ContributorRole,
    @SerialName("steps") val steps: List<PersonSearchStep>,
    @SerialName("inLibrary") val inLibrary: InLibrary,
    @SerialName("coverage") val coverage: List<RoleCoverage>,
    @SerialName("candidates") val candidates: List<PersonCandidate>,
    @SerialName("sources") val sources: List<SourceStatus>,
)
