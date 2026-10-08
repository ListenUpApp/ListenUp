package com.calypsan.listenup.server.matching.person

import com.calypsan.listenup.api.dto.ContributorRole
import com.calypsan.listenup.api.dto.match.ExternalRef
import com.calypsan.listenup.api.dto.match.FieldChoice
import com.calypsan.listenup.api.dto.match.ImageChoice
import com.calypsan.listenup.api.dto.match.PersonCandidateKey
import com.calypsan.listenup.api.dto.match.PersonMatchApply
import com.calypsan.listenup.api.dto.match.PersonMatchReview
import com.calypsan.listenup.api.metadata.MetadataLocale
import com.calypsan.listenup.api.sync.ContributorSyncPayload
import com.calypsan.listenup.server.api.PersonMatchDetails
import com.calypsan.listenup.server.matching.review.AUDNEXUS
import com.calypsan.listenup.server.matching.review.HARDCOVER
import com.calypsan.listenup.server.matching.undo.MatchReceiptStore
import com.calypsan.listenup.server.metadata.EnrichmentCoordinator
import com.calypsan.listenup.server.metadata.ImageStorage
import com.calypsan.listenup.server.metadata.spi.EnrichmentRoutes
import com.calypsan.listenup.server.metadata.spi.MetadataProviderRegistry
import com.calypsan.listenup.server.services.ContributorRepository
import com.calypsan.listenup.server.sync.ChangeBus
import com.calypsan.listenup.server.sync.SyncRegistry
import com.calypsan.listenup.server.testing.SqlTestDatabases
import com.calypsan.listenup.server.testing.shouldSucceed
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.nio.file.Files
import kotlinx.io.files.Path

internal val PERSON_US = MetadataLocale("us")

/** A JPEG's magic number and padding, distinct per [seed], so each photo URL stores a distinct file. */
internal fun jpeg(seed: Int): ByteArray =
    byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte()) + ByteArray(32) { (it + seed).toByte() }

/**
 * Person Review, Apply and Undo over a real database: the real contributor repository and receipt table, fake
 * Audible (Audnexus) and Hardcover profiles, photos served by a mock HTTP engine (a URL containing "broken"
 * answers 404; [photoBytes] picks each URL's bytes).
 */
internal class PersonRig(
    val db: SqlTestDatabases,
) {
    val bus = ChangeBus()
    val contributors = ContributorRepository(db.sql, bus, SyncRegistry())
    val home: String = Files.createTempDirectory("person-home-").also { it.toFile().deleteOnExit() }.toString()
    val audnexus =
        FakeProfileSource(AUDNEXUS).apply {
            profiles = mapOf("B0RAY" to ray("B0RAY", bio = "Audible bio.", photo = AUDIBLE_PHOTO))
        }
    val hardcover =
        FakeProfileSource(HARDCOVER).apply {
            profiles = mapOf("250716" to ray("250716", bio = "Hardcover bio.", photo = HARDCOVER_PHOTO))
        }
    val coordinator =
        EnrichmentCoordinator(MetadataProviderRegistry(listOf(audnexus, hardcover)), EnrichmentRoutes.DEFAULT)
    val reviewer = PersonReviewer(coordinator, displayName = { null })
    val receipts = MatchReceiptStore(db.sql)
    var fault: () -> Unit = {}
    var photoBytes: (String) -> ByteArray = { url -> jpeg(url.length) }
    private val imageStorage =
        ImageStorage(
            HttpClient(
                MockEngine { request ->
                    val url = request.url.toString()
                    if ("broken" in url) {
                        respond(ByteArray(0), HttpStatusCode.NotFound)
                    } else {
                        respond(photoBytes(url), HttpStatusCode.OK, headersOf("Content-Type", "image/jpeg"))
                    }
                },
            ),
        )
    val photoFiles = ContributorPhotoFiles(imageStorage, Path(home))
    val writer = PersonMatchWriter(db.sql, contributors, receipts, now = { 1_000L }, beforeCommit = { fault() })
    val applier = PersonMatchApplier(reviewer, photoFiles, writer, now = { 1_000L })
    val undoer = PersonMatchUndoer(db.sql, contributors, receipts, now = { 2_000L })

    /** Ray Porter as you have him: an old bio and photo, linked to an old Audible profile and a custom ref. */
    suspend fun seedRay(): ContributorSyncPayload {
        rayId = contributors.resolveOrCreate("Ray Porter", null).value
        contributors
            .upsert(
                person().copy(
                    asin = "B0OLD",
                    description = "Old bio.",
                    imagePath = "contributors/old.jpg",
                    externalRefs = listOf(ExternalRef("audible", "B0OLD"), ExternalRef("custom:wiki", "ray")),
                    aliases = listOf("R. Porter"),
                ),
            ).shouldSucceed()
        return person()
    }

    /** Ray Porter's id, once [seedRay] has created him. */
    lateinit var rayId: String

    suspend fun person(): ContributorSyncPayload = contributors.findById(rayId)!!

    suspend fun review(key: PersonCandidateKey = PERSON_KEY): PersonMatchReview =
        reviewer.review(person(), key, PERSON_US).shouldSucceed().review

    /** Everything Review ticks by default. */
    suspend fun defaultRequest(key: PersonCandidateKey = PERSON_KEY): PersonMatchApply {
        val review = review(key)
        return PersonMatchApply(
            candidate = key,
            basedOnRevision = review.basedOnRevision,
            photo = review.photo.defaultChoice,
            biography = review.biography?.defaultChoice ?: FieldChoice.KeepCurrent,
        )
    }

    /** Hardcover's photo and Audible's biography. */
    suspend fun mixedRequest(): PersonMatchApply {
        val review = review()
        return defaultRequest().copy(
            photo =
                ImageChoice.Candidate(
                    review.photo.options
                        .first { it.url == HARDCOVER_PHOTO }
                        .optionId,
                ),
            biography =
                FieldChoice.Option(
                    review.biography!!
                        .options
                        .first { it.sources.first().id == "audible" }
                        .optionId,
                ),
        )
    }

    /** The rig's person Review, Apply and Undo, as the matching service takes them. */
    fun people() =
        PersonMatchDetails(reviewer, applier, undoer) { id ->
            contributors.findById(id.value)?.takeIf {
                it.deletedAt ==
                    null
            }
        }

    suspend fun apply(request: PersonMatchApply) = applier.apply(person(), request, PERSON_US, appliedBy = "u1")
}
