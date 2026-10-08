package com.calypsan.listenup.server.matching.person

import com.calypsan.listenup.api.dto.ContributorRole
import com.calypsan.listenup.api.dto.match.ExternalRef
import com.calypsan.listenup.api.dto.match.PersonCandidateKey
import com.calypsan.listenup.api.metadata.MetadataLocale
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.sync.ContributorSyncPayload
import com.calypsan.listenup.server.metadata.spi.ContributorHitMeta
import com.calypsan.listenup.server.metadata.spi.ContributorMeta
import com.calypsan.listenup.server.metadata.spi.ContributorSource
import com.calypsan.listenup.server.metadata.spi.MetadataProviderId
import kotlinx.coroutines.delay
import kotlin.time.Duration

internal val PERSON_KEY =
    PersonCandidateKey(listOf(ExternalRef("audible", "B0RAY"), ExternalRef("hardcover", "250716")))
internal const val AUDIBLE_PHOTO = "https://example.test/audible-ray.jpg"
internal const val HARDCOVER_PHOTO = "https://example.test/hardcover-ray.jpg"

/**
 * A contributor catalogue with in-memory profiles keyed by its own id, recording every key it is asked for.
 * [slow] delays every answer (virtual time in tests).
 */
internal class FakeProfileSource(
    override val id: MetadataProviderId,
    var profiles: Map<String, AppResult<ContributorMeta?>> = emptyMap(),
    var slow: Duration = Duration.ZERO,
) : ContributorSource {
    val asked = mutableListOf<String>()

    override suspend fun searchContributors(
        name: String,
        locale: MetadataLocale,
    ): AppResult<List<ContributorHitMeta>> = AppResult.Success(emptyList())

    override suspend fun getContributor(
        key: String,
        locale: MetadataLocale,
        refresh: Boolean,
    ): AppResult<ContributorMeta?> {
        asked += key
        if (slow > Duration.ZERO) delay(slow)
        return profiles[key] ?: AppResult.Success(null)
    }
}

/** A profile for Ray Porter at [key]. */
internal fun ray(
    key: String,
    bio: String? = null,
    photo: String? = null,
): AppResult<ContributorMeta?> = AppResult.Success(ContributorMeta(key, "Ray Porter", bio, photo))

/** Ray Porter as you have him, unmatched: no bio, no photo, revision 9. */
internal fun yourRay(
    description: String? = null,
    imagePath: String? = null,
): ContributorSyncPayload =
    ContributorSyncPayload(
        id = "ray",
        name = "Ray Porter",
        sortName = "Porter, Ray",
        revision = 9,
        updatedAt = 1,
        createdAt = 1,
        deletedAt = null,
        description = description,
        imagePath = imagePath,
    )
