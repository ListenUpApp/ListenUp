package com.calypsan.listenup.server.matching.person

import com.calypsan.listenup.api.dto.ContributorRole
import com.calypsan.listenup.api.dto.match.BiographyReview
import com.calypsan.listenup.api.dto.match.FieldChoice
import com.calypsan.listenup.api.dto.match.FieldOption
import com.calypsan.listenup.api.dto.match.FieldState
import com.calypsan.listenup.api.dto.match.FieldValue
import com.calypsan.listenup.api.dto.match.HandEdit
import com.calypsan.listenup.api.dto.match.ImageChoice
import com.calypsan.listenup.api.dto.match.PersonCandidateKey
import com.calypsan.listenup.api.dto.match.PersonMatchReview
import com.calypsan.listenup.api.dto.match.PhotoCandidate
import com.calypsan.listenup.api.dto.match.PhotoReview
import com.calypsan.listenup.api.error.AppError
import com.calypsan.listenup.api.error.MetadataError
import com.calypsan.listenup.api.metadata.ContributorField
import com.calypsan.listenup.api.metadata.FieldSourceKind
import com.calypsan.listenup.api.metadata.MetadataDomain
import com.calypsan.listenup.api.metadata.MetadataLocale
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.sync.ContributorSyncPayload
import com.calypsan.listenup.server.matching.review.REVIEW_DEADLINE
import com.calypsan.listenup.server.matching.review.ReviewKeys
import com.calypsan.listenup.server.metadata.ComposedProfiles
import com.calypsan.listenup.server.metadata.CoreFailure
import com.calypsan.listenup.server.metadata.EnrichmentCoordinator
import com.calypsan.listenup.server.metadata.spi.ContributorMeta
import com.calypsan.listenup.server.metadata.spi.MetadataProviderId
import com.calypsan.listenup.server.metadata.spi.presentedAs
import com.calypsan.listenup.server.metadata.spi.toMetadataSource
import kotlin.time.Duration

/** One reviewed photo tile and the provider whose profile it came from. */
internal data class ReviewedPhoto(
    val candidate: PhotoCandidate,
    val provider: MetadataProviderId,
)

/** One reviewed biography option, the providers that offered it (route order), and the text Apply writes. */
internal data class ReviewedBiography(
    val option: FieldOption,
    val providers: List<MetadataProviderId>,
    val text: String,
)

/** A person Review, with what Apply needs to turn the choices into writes — built the same way for both. */
internal data class PersonReviewModel(
    val contributor: ContributorSyncPayload,
    val review: PersonMatchReview,
    val photos: List<ReviewedPhoto>,
    val biographies: List<ReviewedBiography>,
)

/**
 * Composes a person Review for one people-Find candidate (spec, *Find and Review for people*): each source in the
 * candidate's key reads its own profile; the photo and the biography are reviewed separately. The biography follows
 * book Review's state table (an empty bio fills a gap, an equal one is the same, a hand-edited one is protected);
 * the photo follows decision 6 (a hand-set photo is kept, otherwise the first source's). A candidate whose
 * profiles carry neither a bio nor a photo — an empty regional shell — is not found, as the legacy apply's guard
 * has it. The name is never reviewed.
 */
internal class PersonReviewer(
    private val coordinator: EnrichmentCoordinator,
    private val displayName: suspend (String) -> String?,
    private val deadline: Duration = REVIEW_DEADLINE,
) {
    suspend fun review(
        contributor: ContributorSyncPayload,
        key: PersonCandidateKey,
        role: ContributorRole,
        locale: MetadataLocale,
    ): AppResult<PersonReviewModel> {
        val composed = coordinator.composeProfiles(key.refs, locale, deadline)
        if (composed.profiles.isEmpty()) return AppResult.Failure(failureFor(composed))
        val order = coordinator.routes.domainOrder.getValue(MetadataDomain.CONTRIBUTORS)
        val profiles = order.mapNotNull { provider -> composed.profiles[provider]?.let { provider to it } }
        val photos = photos(profiles)
        val biographies = biographies(profiles)
        if (photos.isEmpty() && biographies.isEmpty()) {
            return AppResult.Failure(
                MetadataError.NotFound(debugInfo = "person review: empty profiles (regional shell)"),
            )
        }
        val photoSetByHand = contributor.fieldProvenance[ContributorField.PHOTO]?.kind == FieldSourceKind.USER
        return AppResult.Success(
            PersonReviewModel(
                contributor = contributor,
                review =
                    PersonMatchReview(
                        candidate = key,
                        role = role,
                        basedOnRevision = contributor.revision,
                        photo =
                            PhotoReview(
                                current = contributor.imagePath,
                                setByHand = photoSetByHand,
                                options = photos.map { it.candidate },
                                defaultChoice =
                                    photos.firstOrNull()?.takeUnless { photoSetByHand }?.let {
                                        ImageChoice.Candidate(it.candidate.optionId)
                                    } ?: ImageChoice.KeepCurrent,
                            ),
                        biography = biographyReview(contributor, biographies),
                    ),
                photos = photos,
                biographies = biographies,
            ),
        )
    }

    private fun photos(profiles: List<Pair<MetadataProviderId, ContributorMeta>>) =
        profiles
            .mapNotNull { (provider, profile) ->
                profile.imageUrl?.trim()?.takeIf { it.isNotEmpty() }?.let {
                    provider to
                        it
                }
            }.distinctBy { (_, url) -> url }
            .map { (provider, url) ->
                ReviewedPhoto(
                    PhotoCandidate(
                        optionId = ReviewKeys.optionId(provider.presentedAs().value, "photo:$url"),
                        source = provider.toMetadataSource(),
                        url = url,
                    ),
                    provider,
                )
            }

    private fun biographies(profiles: List<Pair<MetadataProviderId, ContributorMeta>>) =
        profiles
            .mapNotNull { (provider, profile) ->
                profile.description?.takeIf { it.isNotBlank() }?.let { provider to it }
            }.groupBy { (_, text) -> ReviewKeys.keyOf(FieldValue.Text(text), html = true) }
            .map { (key, group) ->
                val providers = group.map { it.first }
                ReviewedBiography(
                    option =
                        FieldOption(
                            optionId = ReviewKeys.optionId(providers.first().presentedAs().value, key),
                            value = FieldValue.Text(group.first().second),
                            sources = providers.map { it.toMetadataSource() }.distinctBy { it.id },
                        ),
                    providers = providers,
                    text = group.first().second,
                )
            }

    private suspend fun biographyReview(
        contributor: ContributorSyncPayload,
        biographies: List<ReviewedBiography>,
    ): BiographyReview? {
        val best = biographies.firstOrNull() ?: return null
        val current = contributor.description?.takeIf { it.isNotBlank() }
        val edit = contributor.fieldProvenance[ContributorField.BIOGRAPHY]?.takeIf { it.kind == FieldSourceKind.USER }
        val (state, choice) =
            when {
                current == null -> {
                    FieldState.FILLS_GAP to FieldChoice.Option(best.option.optionId)
                }

                ReviewKeys.keyOf(FieldValue.Text(current), html = true) ==
                    ReviewKeys.keyOf(best.option.value, html = true) -> {
                    FieldState.SAME to FieldChoice.KeepCurrent
                }

                edit != null -> {
                    FieldState.USER_EDITED to FieldChoice.KeepCurrent
                }

                else -> {
                    FieldState.CHANGES to FieldChoice.Option(best.option.optionId)
                }
            }
        return BiographyReview(
            current = current,
            options = biographies.map { it.option },
            defaultChoice = choice,
            state = state,
            handEdit =
                edit?.let {
                    HandEdit(
                        byUserId = it.by,
                        byName = it.by?.let { id -> displayName(id)?.takeIf(String::isNotBlank) },
                        at = it.at.takeIf { at -> at > 0 },
                    )
                },
        )
    }

    /** No key source answered with a profile: a rate limit, else a timeout, else unavailable, else not found. */
    private fun failureFor(composed: ComposedProfiles): AppError {
        val failures = composed.failures.values
        val rateLimited = failures.filterIsInstance<CoreFailure.RateLimited>()
        return when {
            rateLimited.isNotEmpty() -> {
                MetadataError.ExternalRateLimited(
                    debugInfo = "person review: a candidate source is rate-limited",
                    retryAfterSeconds = rateLimited.mapNotNull { it.retryAfterSeconds }.maxOrNull(),
                )
            }

            failures.any { it == CoreFailure.TimedOut } -> {
                MetadataError.ExternalTimeout(debugInfo = "person review: a candidate source didn't answer in time")
            }

            composed.asked.isNotEmpty() && failures.size == composed.asked.size -> {
                MetadataError.ExternalUnavailable(debugInfo = "person review: every candidate source failed")
            }

            else -> {
                MetadataError.NotFound(debugInfo = "person review: no candidate source has this person")
            }
        }
    }
}
