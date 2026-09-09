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
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.io.IOException
import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem

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
            storage: ImageStorage = storageServing(ONE_PX_PNG),
            imageHome: Path = Path(Files.createTempDirectory("applier-test-").toString()),
        ): ContributorMetadataApplier =
            ContributorMetadataApplier(
                contributorRepository = repo,
                imageStorage = storage,
                coordinator = coordinatorWith(profile),
                imageHome = imageHome,
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
                    // The extension comes from the sniffed kind, not the URL's claim.
                    updated.imagePath shouldBe "contributors/${hashBytesSha256(ONE_PX_PNG)}.png"
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

        test("a contributor photo response that declares a non-image type is refused") {
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
                    val htmlStorage =
                        ImageStorage(
                            HttpClient(
                                MockEngine {
                                    respond(
                                        content = ByteArray(8),
                                        status = HttpStatusCode.OK,
                                        headers = headersOf(HttpHeaders.ContentType, ContentType.Text.Html.toString()),
                                    )
                                },
                            ),
                        )

                    applier(repo, profile, storage = htmlStorage)
                        .apply(ContributorId("c-1"), "B0ASIN", MetadataLocale("us"))
                        .shouldBeInstanceOf<AppResult.Success<Unit>>()

                    val updated = repo.findById("c-1")
                    updated.shouldNotBeNull()
                    // The existing photo survives: nothing that isn't an image reaches the disk.
                    updated.imagePath shouldBe "contributors/existing.jpg"
                }
            }
        }

        test("a contributor photo whose bytes are not an image is rejected and no file is written") {
            withSqlDatabase {
                val repo = ContributorRepository(db = sql, bus = ChangeBus(), registry = SyncRegistry())
                runTest {
                    repo.upsert(existingPayload)
                    val profile = photoProfile()
                    val imageHome = Path(Files.createTempDirectory("applier-notimage-").toString())
                    // Declares itself an image, but the bytes carry no image magic number. The
                    // declared type is the remote's claim; the sniff is what decides.
                    val liar = storageServing("this is not an image".encodeToByteArray())

                    applier(repo, profile, storage = liar, imageHome = imageHome)
                        .apply(ContributorId("c-1"), "B0ASIN", MetadataLocale("us"))
                        .shouldBeInstanceOf<AppResult.Success<Unit>>()

                    val updated = repo.findById("c-1")
                    updated.shouldNotBeNull()
                    updated.imagePath shouldBe "contributors/existing.jpg"
                    contributorFiles(imageHome) shouldBe emptyList()
                }
            }
        }

        test("an oversized contributor photo is rejected and no file is written") {
            withSqlDatabase {
                val repo = ContributorRepository(db = sql, bus = ChangeBus(), registry = SyncRegistry())
                runTest {
                    repo.upsert(existingPayload)
                    val imageHome = Path(Files.createTempDirectory("applier-oversize-").toString())

                    ContributorMetadataApplier(
                        contributorRepository = repo,
                        imageStorage = storageServing(ONE_PX_PNG),
                        coordinator = coordinatorWith(photoProfile()),
                        imageHome = imageHome,
                        photoMaxBytes = TINY_PHOTO_CEILING_BYTES,
                    ).apply(ContributorId("c-1"), "B0ASIN", MetadataLocale("us"))
                        .shouldBeInstanceOf<AppResult.Success<Unit>>()

                    val updated = repo.findById("c-1")
                    updated.shouldNotBeNull()
                    updated.imagePath shouldBe "contributors/existing.jpg"
                    contributorFiles(imageHome) shouldBe emptyList()
                }
            }
        }

        test("a valid contributor photo is stored under its content-addressed name") {
            withSqlDatabase {
                val repo = ContributorRepository(db = sql, bus = ChangeBus(), registry = SyncRegistry())
                runTest {
                    repo.upsert(existingPayload)
                    val imageHome = Path(Files.createTempDirectory("applier-valid-").toString())

                    applier(repo, photoProfile(), imageHome = imageHome)
                        .apply(ContributorId("c-1"), "B0ASIN", MetadataLocale("us"))
                        .shouldBeInstanceOf<AppResult.Success<Unit>>()

                    val sha = hashBytesSha256(ONE_PX_PNG)
                    val updated = repo.findById("c-1")
                    updated.shouldNotBeNull()
                    // The SHA is the filename, not the contributor id: the path is the cache
                    // version a client keys on, so a new photo must produce a new path.
                    updated.imagePath shouldBe "contributors/$sha.png"
                    contributorFiles(imageHome) shouldBe listOf("$sha.png")
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

/** Smaller than [ONE_PX_PNG], so the store's cap is what refuses it. */
private const val TINY_PHOTO_CEILING_BYTES = 8L

/** A 1×1 PNG — the smallest input that survives the store's magic-number sniff. */
private val ONE_PX_PNG: ByteArray =
    java.util.Base64
        .getDecoder()
        .decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg==",
        )

/** An [ImageStorage] whose every request answers with [bytes], declared as a PNG. */
private fun storageServing(bytes: ByteArray): ImageStorage =
    ImageStorage(
        HttpClient(
            MockEngine {
                respond(
                    content = bytes,
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, ContentType.Image.PNG.toString()),
                )
            },
        ),
    )

/** Every file currently sitting in `<imageHome>/contributors`, or empty when the dir is absent. */
private fun contributorFiles(imageHome: Path): List<String> {
    val dir = Path(imageHome.toString(), "contributors")
    if (!SystemFileSystem.exists(dir)) return emptyList()
    return SystemFileSystem.list(dir).map { it.name }.sorted()
}

/** The canned profile used by the photo-handling tests. */
private fun photoProfile(): ContributorMeta =
    ContributorMeta(
        key = "B0ASIN",
        name = "Brandon Sanderson",
        description = "New bio.",
        imageUrl = "https://img.example/p.jpg",
    )

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
