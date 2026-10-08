package com.calypsan.listenup.client.presentation.match

import com.calypsan.listenup.api.dto.match.BiographyReview
import com.calypsan.listenup.api.dto.match.FieldChoice
import com.calypsan.listenup.api.dto.match.FieldState
import com.calypsan.listenup.api.dto.match.ImageChoice
import com.calypsan.listenup.api.dto.match.PersonMatchApply
import com.calypsan.listenup.api.dto.match.PersonMatchReview
import com.calypsan.listenup.api.dto.match.PhotoReview
import com.calypsan.listenup.api.error.AppError

/**
 * What a person changed in one candidate's person Review, as deltas over the server's defaults — the photo and
 * the biography, each its own decision. Like [ReviewChoices], a choice whose option vanished on a reload falls
 * back to the server's default, so nothing here can name an option the server didn't offer.
 */
internal data class PersonReviewChoices(
    internal val photo: ImageChoice? = null,
    internal val lastPhoto: String? = null,
    internal val biography: FieldChoice? = null,
    internal val lastBiography: String? = null,
) {
    fun choosePhoto(choice: ImageChoice): PersonReviewChoices =
        copy(photo = choice, lastPhoto = (choice as? ImageChoice.Candidate)?.optionId ?: lastPhoto)

    fun chooseBiography(choice: FieldChoice): PersonReviewChoices =
        copy(biography = choice, lastBiography = (choice as? FieldChoice.Option)?.optionId ?: lastBiography)

    /** Ticking the photo restores the last photo chosen, else the server's pick, else the first source's. */
    fun setPhotoTicked(
        review: PersonMatchReview,
        ticked: Boolean,
    ): PersonReviewChoices {
        val proposed = proposedPhotoId(review.photo) ?: return this
        return if (ticked) {
            choosePhoto(ImageChoice.Candidate(proposed))
        } else {
            copy(photo = ImageChoice.KeepCurrent, lastPhoto = proposed)
        }
    }

    /** Ticking the biography restores the last source chosen, else the server's pick, else the route's first. */
    fun setBiographyTicked(
        review: PersonMatchReview,
        ticked: Boolean,
    ): PersonReviewChoices {
        val bio = review.biography ?: return this
        val proposed = proposedBiographyId(bio) ?: return this
        return if (ticked) {
            chooseBiography(FieldChoice.Option(proposed))
        } else {
            copy(biography = FieldChoice.KeepCurrent, lastBiography = proposed)
        }
    }

    fun photoFor(review: PhotoReview): ImageChoice =
        when (val explicit = photo) {
            null -> {
                review.defaultChoice
            }

            ImageChoice.KeepCurrent -> {
                ImageChoice.KeepCurrent
            }

            is ImageChoice.Candidate -> {
                if (review.options.any {
                        it.optionId == explicit.optionId
                    }
                ) {
                    explicit
                } else {
                    review.defaultChoice
                }
            }
        }

    fun biographyFor(review: BiographyReview): FieldChoice =
        when (val explicit = biography) {
            null -> {
                review.defaultChoice
            }

            FieldChoice.KeepCurrent -> {
                FieldChoice.KeepCurrent
            }

            is FieldChoice.Option -> {
                if (review.options.any {
                        it.optionId == explicit.optionId
                    }
                ) {
                    explicit
                } else {
                    review.defaultChoice
                }
            }
        }

    private fun proposedPhotoId(review: PhotoReview): String? {
        val wanted =
            (photoFor(review) as? ImageChoice.Candidate)?.optionId
                ?: lastPhoto
                ?: (review.defaultChoice as? ImageChoice.Candidate)?.optionId
        return (review.options.firstOrNull { it.optionId == wanted } ?: review.options.firstOrNull())?.optionId
    }

    private fun proposedBiographyId(review: BiographyReview): String? {
        val wanted =
            (biographyFor(review) as? FieldChoice.Option)?.optionId
                ?: lastBiography
                ?: (review.defaultChoice as? FieldChoice.Option)?.optionId
        return (review.options.firstOrNull { it.optionId == wanted } ?: review.options.firstOrNull())?.optionId
    }

    /** Projects [review] and these choices into what person Review renders. Pure, so every platform agrees. */
    fun project(
        candidate: PersonCandidateUi,
        review: PersonMatchReview,
        applying: Boolean,
        applyError: AppError?,
    ): PersonReviewUiState.Ready {
        val photo = photoUi(review.photo)
        val biography = review.biography?.let(::biographyUi)
        return PersonReviewUiState.Ready(
            candidate = candidate,
            photo = photo,
            biography = biography,
            applyBar =
                PersonApplySummary(
                    photo = photo?.isTicked == true,
                    biography = biography?.isTicked == true,
                    sources =
                        (
                            listOfNotNull(photo?.chosen?.source) +
                                biography
                                    ?.takeIf { it.isTicked }
                                    ?.run { proposed.sources }
                                    .orEmpty()
                        ).distinct(),
                ),
            applying = applying,
            applyError = applyError,
        )
    }

    private fun photoUi(review: PhotoReview): PhotoUi? {
        val proposedId = proposedPhotoId(review) ?: return null
        val options = review.options.map { PhotoOptionUi(it.optionId, it.source, it.url) }
        return PhotoUi(
            currentPath = review.current,
            setByHand = review.setByHand,
            state =
                when {
                    review.current == null -> FieldState.FILLS_GAP
                    review.setByHand -> FieldState.USER_EDITED
                    else -> FieldState.CHANGES
                },
            options = options,
            choice = photoFor(review),
            proposed = options.first { it.optionId == proposedId },
        )
    }

    private fun biographyUi(review: BiographyReview): BiographyUi? {
        val proposedId = proposedBiographyId(review) ?: return null
        val options = review.options.map { FieldOptionUi(it.optionId, it.value, it.sources) }
        return BiographyUi(
            state = review.state,
            current = review.current,
            options = options,
            choice = biographyFor(review),
            proposed = options.first { it.optionId == proposedId },
            handEdit = review.handEdit,
        )
    }

    /** The one Apply request: the photo and the biography, chosen separately. */
    fun toApply(review: PersonMatchReview): PersonMatchApply =
        PersonMatchApply(
            candidate = review.candidate,
            basedOnRevision = review.basedOnRevision,
            photo = photoFor(review.photo),
            biography = review.biography?.let(::biographyFor) ?: FieldChoice.KeepCurrent,
        )
}
