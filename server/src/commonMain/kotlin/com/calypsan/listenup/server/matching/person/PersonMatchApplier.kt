package com.calypsan.listenup.server.matching.person

import com.calypsan.listenup.api.dto.match.AppliedChange
import com.calypsan.listenup.api.dto.match.ExternalRef
import com.calypsan.listenup.api.dto.match.FieldChoice
import com.calypsan.listenup.api.dto.match.FieldValue
import com.calypsan.listenup.api.dto.match.ImageChoice
import com.calypsan.listenup.api.dto.match.MatchReceipt
import com.calypsan.listenup.api.dto.match.PersonMatchApply
import com.calypsan.listenup.api.error.MetadataError
import com.calypsan.listenup.api.metadata.MetadataLocale
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.sync.ContributorSyncPayload
import com.calypsan.listenup.server.matching.review.ReviewKeys
import com.calypsan.listenup.server.metadata.spi.MetadataProviderId
import com.calypsan.listenup.server.metadata.spi.toMetadataSource

/** A photo Apply writes: the stored file, and the provider it came from. */
internal data class PlannedPhoto(
    val path: String,
    val provider: MetadataProviderId,
)

/** A biography Apply writes: the text, and the provider it came from (the first that offered it). */
internal data class PlannedBiography(
    val text: String,
    val provider: MetadataProviderId,
)

/**
 * Everything one person Apply writes: the [photo] and [biography] when they change, the candidate's [refs], and
 * the receipt's [changes]. Never the name.
 */
internal data class PersonMatchPlan(
    val contributorId: String,
    val photo: PlannedPhoto?,
    val biography: PlannedBiography?,
    val refs: List<ExternalRef>,
) {
    val changes: List<AppliedChange> =
        listOfNotNull(
            photo?.let { AppliedChange.Photo(it.provider.toMetadataSource()) },
            biography?.let { AppliedChange.Biography(it.provider.toMetadataSource()) },
        )
}

/**
 * Match details' person Apply (spec, *Find and Review for people*): re-derive the Review, check every choice
 * against it, fetch the chosen photo, then write the photo, the biography, the refs and the provenance in one
 * transaction with a receipt. The photo and the biography are separate decisions; the name is never written.
 * Nothing is written on any failure: [MetadataError.ReviewOutdated] when the person or a chosen option moved,
 * [MetadataError.CoverDownloadFailed] when the photo can't be fetched.
 */
internal class PersonMatchApplier(
    private val reviewer: PersonReviewer,
    private val photoFiles: ContributorPhotoFiles,
    private val writer: PersonMatchWriter,
    private val now: () -> Long,
) {
    suspend fun apply(
        contributor: ContributorSyncPayload,
        request: PersonMatchApply,
        locale: MetadataLocale,
        appliedBy: String,
    ): AppResult<MatchReceipt> {
        if (contributor.revision != request.basedOnRevision) return outdated("person moved since the review")
        val model =
            when (val reviewed = reviewer.review(contributor, request.candidate, request.role, locale)) {
                is AppResult.Success -> reviewed.data
                is AppResult.Failure -> return reviewed
            }
        val photo =
            when (val choice = request.photo) {
                ImageChoice.KeepCurrent -> {
                    null
                }

                is ImageChoice.Candidate -> {
                    model.photos.firstOrNull { it.candidate.optionId == choice.optionId }
                        ?: return outdated("photo option ${choice.optionId} is no longer offered")
                }
            }
        val biography =
            when (val choice = request.biography) {
                FieldChoice.KeepCurrent -> {
                    null
                }

                is FieldChoice.Option -> {
                    model.biographies.firstOrNull { it.option.optionId == choice.optionId }
                        ?: return outdated("biography option ${choice.optionId} is no longer offered")
                }
            }
        val photoPath =
            photo?.let {
                photoFiles.store(it.candidate.url)
                    ?: return AppResult.Failure(
                        MetadataError.CoverDownloadFailed(debugInfo = "person photo fetch failed"),
                    )
            }
        val plan =
            PersonMatchPlan(
                contributorId = contributor.id,
                // The photo you already have, or the bio you already have, changes nothing: Apply writes only what
                // you can see change.
                photo = photoPath?.takeIf { it != contributor.imagePath }?.let { PlannedPhoto(it, photo.provider) },
                biography =
                    biography
                        ?.takeUnless { it.sameAs(contributor.description) }
                        ?.let { PlannedBiography(it.text, it.providers.first()) },
                refs = request.candidate.refs,
            )
        return writer.write(plan, basedOnRevision = request.basedOnRevision, appliedBy = appliedBy, at = now())
    }

    private fun ReviewedBiography.sameAs(current: String?): Boolean =
        current != null &&
            ReviewKeys.keyOf(FieldValue.Text(current), html = true) == ReviewKeys.keyOf(option.value, html = true)

    private fun outdated(why: String) = AppResult.Failure(MetadataError.ReviewOutdated(debugInfo = why))
}
