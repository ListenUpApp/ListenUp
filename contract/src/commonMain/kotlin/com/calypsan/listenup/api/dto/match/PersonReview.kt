package com.calypsan.listenup.api.dto.match

import com.calypsan.listenup.api.dto.ContributorRole
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** One source's photo of the person, a tile in the photo choice. */
@Serializable
@SerialName("PhotoCandidate")
data class PhotoCandidate(
    @SerialName("optionId") val optionId: String,
    @SerialName("source") val source: MetadataSource,
    @SerialName("url") val url: String,
)

/**
 * The photo half of a person Review. [current] is the stored photo path clients already build the image URL from,
 * null when the person has none; [setByHand] when someone uploaded it. [defaultChoice] keeps a hand-set photo, and
 * otherwise takes the first source's (decision 6, applied to the photo). No [options] is "Keep current" only.
 */
@Serializable
@SerialName("PhotoReview")
data class PhotoReview(
    @SerialName("current") val current: String?,
    @SerialName("setByHand") val setByHand: Boolean,
    @SerialName("options") val options: List<PhotoCandidate>,
    @SerialName("defaultChoice") val defaultChoice: ImageChoice,
)

/**
 * The biography half of a person Review, with the same state rules as a book field: [current] Yours, each source's
 * [options], the [state] and the [defaultChoice] it implies, and who edited it by hand.
 */
@Serializable
@SerialName("BiographyReview")
data class BiographyReview(
    @SerialName("current") val current: String?,
    @SerialName("options") val options: List<FieldOption>,
    @SerialName("defaultChoice") val defaultChoice: FieldChoice,
    @SerialName("state") val state: FieldState,
    @SerialName("handEdit") val handEdit: HandEdit?,
)

/**
 * A person Review for one Find candidate: the photo and the biography, each its own decision. [biography] is null
 * when no source has one. The name is never offered — matching a person never renames them. Apply sends
 * [basedOnRevision] back. [role] echoes the role a client from before role-free matching named; null otherwise.
 */
@Serializable
@SerialName("PersonMatchReview")
data class PersonMatchReview(
    @SerialName("candidate") val candidate: PersonCandidateKey,
    @SerialName("basedOnRevision") val basedOnRevision: Long,
    @SerialName("photo") val photo: PhotoReview,
    @SerialName("biography") val biography: BiographyReview?,
    @SerialName("role") val role: ContributorRole? = null,
)

/**
 * What one person Apply writes, in one transaction: the [photo] and the [biography], chosen separately, plus the
 * candidate's refs. Both kept is still a match: it links the person to the candidate. [role] is what a client
 * from before role-free matching still sends; it is ignored.
 */
@Serializable
@SerialName("PersonMatchApply")
data class PersonMatchApply(
    @SerialName("candidate") val candidate: PersonCandidateKey,
    @SerialName("basedOnRevision") val basedOnRevision: Long,
    @SerialName("photo") val photo: ImageChoice,
    @SerialName("biography") val biography: FieldChoice,
    @SerialName("role") val role: ContributorRole? = null,
)
