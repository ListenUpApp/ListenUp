package com.calypsan.listenup.server.api

import com.calypsan.listenup.api.error.MetadataError
import com.calypsan.listenup.api.metadata.MetadataLocale
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.sync.ContributorSyncPayload
import com.calypsan.listenup.core.ContributorId
import com.calypsan.listenup.server.io.hashBytesSha256
import com.calypsan.listenup.server.metadata.EnrichmentCoordinator
import com.calypsan.listenup.server.metadata.ImageStorage
import com.calypsan.listenup.server.metadata.spi.ContributorHitMeta
import com.calypsan.listenup.server.metadata.spi.ContributorMeta
import com.calypsan.listenup.server.metadata.spi.ContributorSource
import com.calypsan.listenup.server.metadata.spi.EnrichmentRoutes
import com.calypsan.listenup.server.metadata.spi.MetadataProviderId
import com.calypsan.listenup.server.metadata.spi.MetadataProviderRegistry
import com.calypsan.listenup.server.services.ContributorRepository
import com.calypsan.listenup.server.sync.ChangeBus
import com.calypsan.listenup.server.sync.SyncRegistry
import com.calypsan.listenup.server.testing.withSqlDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import java.io.IOException
import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import kotlinx.io.files.Path

/**
 * Non-destructive apply semantics (ABS-verified): a blank profile field never
 * overwrites the contributor's existing value, a failed photo download keeps
 * the existing photo, and an all-blank profile is an honest miss.
 */
class ContributorMetadataApplierTest :
    FunSpec({

        val existingPayload =
            ContributorSyncPayload(
                id = "c-1",
                name = "Brandon Sanderson",
                sortName = "Sanderson, Brandon",
                revision = 0L,
                updatedAt = 0L,
                createdAt = 0L,
                deletedAt = null,
                asin = null,
                description = "Existing bio.",
                imagePath = "contributors/existing.jpg",
            )

        fun coordinatorWith(profile: ContributorMeta?): EnrichmentCoordinator =
            EnrichmentCoordinator(
                registry = MetadataProviderRegistry(providers = listOf(StaticContributorSource(profile))),
                routes = EnrichmentRoutes.DEFAULT,
            )

        fun applier(
            repo: ContributorRepository,
            profile: ContributorMeta?,
            storage: ImageStorage = ImageStorage(HttpClient(MockEngine { respond(ByteArray(8), HttpStatusCode.OK) })),
        ): ContributorMetadataApplier =
            ContributorMetadataApplier(
                contributorRepository = repo,
                imageStorage = storage,
                coordinator = coordinatorWith(profile),
                imageHome = Path(Files.createTempDirectory("applier-test-").toString()),
            )

        test("a blank-bio profile keeps the existing biography") {
            withSqlDatabase {
                val repo = ContributorRepository(db = sql, bus = ChangeBus(), registry = SyncRegistry())
                runTest {
                    repo.upsert(existingPayload)
                    val profile =
                        ContributorMeta(
                            key = "B0ASIN",
                            name = "Brandon Sanderson",
                            description = null,
                            imageUrl = "https://img.example/p.jpg",
                        )

                    applier(repo, profile)
                        .apply(ContributorId("c-1"), "B0ASIN", MetadataLocale("us"))
                        .shouldBeInstanceOf<AppResult.Success<Unit>>()

                    val updated = repo.findById("c-1")
                    updated.shouldNotBeNull()
                    updated.description shouldBe "Existing bio."
                    updated.asin shouldBe "B0ASIN"
                    // Positive path: a successful download IS applied — proves the coalesce
                    // (`imagePath ?: existing.imagePath`) isn't silently swallowing the new value too.
                    updated.imagePath shouldBe "contributors/${hashBytesSha256(ByteArray(8))}.jpg"
                }
            }
        }

        test("a whitespace-only bio profile keeps the existing biography") {
            withSqlDatabase {
                val repo = ContributorRepository(db = sql, bus = ChangeBus(), registry = SyncRegistry())
                runTest {
                    repo.upsert(existingPayload)
                    val profile =
                        ContributorMeta(
                            key = "B0ASIN",
                            name = "Brandon Sanderson",
                            description = "   ",
                            imageUrl = "https://img.example/p.jpg",
                        )

                    applier(repo, profile)
                        .apply(ContributorId("c-1"), "B0ASIN", MetadataLocale("us"))
                        .shouldBeInstanceOf<AppResult.Success<Unit>>()

                    val updated = repo.findById("c-1")
                    updated.shouldNotBeNull()
                    // Pins the `isNotBlank()` check specifically — a whitespace-only description is
                    // non-null, so a regression back to a bare `!= null` check would silently pass
                    // this value through and wipe the existing biography.
                    updated.description shouldBe "Existing bio."
                }
            }
        }

        test("a whitespace-only image URL keeps the existing photo") {
            withSqlDatabase {
                val repo = ContributorRepository(db = sql, bus = ChangeBus(), registry = SyncRegistry())
                runTest {
                    repo.upsert(existingPayload)
                    val profile =
                        ContributorMeta(key = "B0ASIN", name = "Brandon Sanderson", description = "New bio.", imageUrl = "   ")

                    applier(repo, profile)
                        .apply(ContributorId("c-1"), "B0ASIN", MetadataLocale("us"))
                        .shouldBeInstanceOf<AppResult.Success<Unit>>()

                    val updated = repo.findById("c-1")
                    updated.shouldNotBeNull()
                    updated.imagePath shouldBe "contributors/existing.jpg"
                    updated.description shouldBe "New bio."
                }
            }
        }

        test("a failed photo download keeps the existing image path") {
            withSqlDatabase {
                val repo = ContributorRepository(db = sql, bus = ChangeBus(), registry = SyncRegistry())
                runTest {
                    repo.upsert(existingPayload)
                    val profile =
                        ContributorMeta(
                            key = "B0ASIN",
                            name = "Brandon Sanderson",
                            description = "New bio.",
                            imageUrl = "https://img.example/p.jpg",
                        )
                    val failingStorage = ImageStorage(HttpClient(MockEngine { throw IOException("network down") }))

                    applier(repo, profile, storage = failingStorage)
                        .apply(ContributorId("c-1"), "B0ASIN", MetadataLocale("us"))
                        .shouldBeInstanceOf<AppResult.Success<Unit>>()

                    val updated = repo.findById("c-1")
                    updated.shouldNotBeNull()
                    updated.imagePath shouldBe "contributors/existing.jpg"
                    updated.description shouldBe "New bio."
                }
            }
        }

        test("an all-blank profile is an honest miss — NotFound, nothing written") {
            withSqlDatabase {
                val repo = ContributorRepository(db = sql, bus = ChangeBus(), registry = SyncRegistry())
                runTest {
                    repo.upsert(existingPayload)
                    val profile =
                        ContributorMeta(key = "B0ASIN", name = "Brandon Sanderson", description = null, imageUrl = null)

                    val result = applier(repo, profile).apply(ContributorId("c-1"), "B0ASIN", MetadataLocale("us"))

                    result.shouldBeInstanceOf<AppResult.Failure>()
                    result.error.shouldBeInstanceOf<MetadataError.NotFound>()
                    val unchanged = repo.findById("c-1")
                    unchanged.shouldNotBeNull()
                    unchanged.asin shouldBe null
                    unchanged.description shouldBe "Existing bio."
                }
            }
        }
    })

/** A [ContributorSource] returning one canned profile (stands in for Audnexus). */
private class StaticContributorSource(
    private val profile: ContributorMeta?,
) : ContributorSource {
    override val id: MetadataProviderId = MetadataProviderId.AUDNEXUS

    override suspend fun searchContributors(
        name: String,
        locale: MetadataLocale,
    ): AppResult<List<ContributorHitMeta>> = AppResult.Success(emptyList())

    override suspend fun getContributor(
        key: String,
        locale: MetadataLocale,
        refresh: Boolean,
    ): AppResult<ContributorMeta?> = AppResult.Success(profile)
}
