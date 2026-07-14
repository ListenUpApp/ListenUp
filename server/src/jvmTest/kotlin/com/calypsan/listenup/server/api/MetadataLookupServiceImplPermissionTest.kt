@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.calypsan.listenup.server.api

import com.calypsan.listenup.api.dto.MetadataApplySelection
import com.calypsan.listenup.api.error.AuthError
import com.calypsan.listenup.api.metadata.AudibleRegion
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.server.auth.UserPermissionPolicy
import com.calypsan.listenup.server.cover.CoverImageStore
import com.calypsan.listenup.server.db.UserRoleColumn
import com.calypsan.listenup.server.media.ImageStore
import com.calypsan.listenup.server.metadata.ImageStorage
import com.calypsan.listenup.server.metadata.audible.AudibleApi
import com.calypsan.listenup.server.metadata.audible.AudibleBook
import com.calypsan.listenup.server.metadata.audible.AudibleChapter
import com.calypsan.listenup.server.metadata.audible.AudibleContributorProfile
import com.calypsan.listenup.server.metadata.audible.AudibleSearchResult
import com.calypsan.listenup.server.metadata.audible.ProductTag
import com.calypsan.listenup.server.metadata.audible.SearchParams
import com.calypsan.listenup.server.metadata.itunes.ITunesApi
import com.calypsan.listenup.server.metadata.itunes.ITunesCoverHit
import com.calypsan.listenup.server.metadata.provider.AudibleMetadataProvider
import com.calypsan.listenup.server.services.BookRepository
import com.calypsan.listenup.server.services.ContributorRepository
import com.calypsan.listenup.server.metadata.spi.MetadataProviderRegistry
import com.calypsan.listenup.server.services.CoverSearchService
import com.calypsan.listenup.server.services.GenreRepository
import com.calypsan.listenup.server.services.MetadataCacheRepository
import com.calypsan.listenup.server.services.MetadataService
import com.calypsan.listenup.server.services.SeriesRepository
import com.calypsan.listenup.server.sync.ChangeBus
import com.calypsan.listenup.server.sync.SyncRegistry
import com.calypsan.listenup.server.testing.SqlTestDatabases
import com.calypsan.listenup.server.testing.memberPrincipal
import com.calypsan.listenup.server.testing.rootPrincipal
import com.calypsan.listenup.server.testing.seedTestLibraryAndFolder
import com.calypsan.listenup.server.testing.seedTestUser
import com.calypsan.listenup.server.testing.testEnrichmentDeps
import com.calypsan.listenup.server.testing.withSqlDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import kotlinx.io.files.Path

/**
 * canEdit-gate tests for [MetadataLookupServiceImpl] (closes MA holistic-review finding I1).
 *
 * `applyBookMetadata` is the representative gated op; the privileged/state-changing ops
 * (`applyBookMetadata`/`applyContributorMetadata`/`refreshBookMetadata`) share the identical
 * first-statement `requireCanEdit()` guard. The search/fetch reads stay open. The MEMBER-deny
 * case short-circuits before any external fetch; the ADMIN case proves the gate passed (it
 * proceeds into the real applier and fails only because the book is absent — not a
 * PermissionDenied).
 */
class MetadataLookupServiceImplPermissionTest :
    FunSpec({

        test("applyBookMetadata is denied for a MEMBER without canEdit") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestUser("member", UserRoleColumn.MEMBER, canEdit = false)
                val service = makeMetadataPermService(this).copyWith(memberPrincipal("member"))
                runTest {
                    val result = service.applyBookMetadata(BookId("no-such-book"), "ASIN1", AudibleRegion.US, ALL_SELECTED)

                    val failure = result.shouldBeInstanceOf<AppResult.Failure>()
                    failure.error.shouldBeInstanceOf<AuthError.PermissionDenied>()
                }
            }
        }

        test("applyBookMetadata passes the gate for an ADMIN (no PermissionDenied)") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                val service = makeMetadataPermService(this).copyWith(rootPrincipal())
                runTest {
                    val result = service.applyBookMetadata(BookId("no-such-book"), "ASIN1", AudibleRegion.US, ALL_SELECTED)

                    // Gate passed → reaches the applier, which fails because the book is absent.
                    // The point: it is NOT a PermissionDenied.
                    val failure = result.shouldBeInstanceOf<AppResult.Failure>()
                    (failure.error is AuthError.PermissionDenied) shouldBe false
                }
            }
        }
    })

private val ALL_SELECTED =
    MetadataApplySelection(
        title = true,
        subtitle = true,
        description = true,
        publisher = true,
        releaseDate = true,
        language = true,
        cover = true,
        authorAsins = emptySet(),
        narratorAsins = emptySet(),
        seriesAsins = emptySet(),
    )

private fun makeMetadataPermService(dbs: SqlTestDatabases): MetadataLookupServiceImpl {
    val tempDir = Files.createTempDirectory("metadata-perm-").toAbsolutePath()
    val bus = ChangeBus()
    val registry = SyncRegistry()
    val contributorRepo = ContributorRepository(dbs.sql, bus, registry)
    val seriesRepo = SeriesRepository(dbs.sql, bus, registry)
    val genreRepo = GenreRepository(dbs.sql, bus, registry)
    val bookRepo = BookRepository(dbs.sql, bus, registry, dbs.driver, contributorRepo, seriesRepo, genreRepo)
    val metadataService =
        MetadataService(
            audible = EmptyAudibleApi(),
            itunes = NoOpITunesApiForPerm(),
            cache = MetadataCacheRepository(dbs.sql),
        )
    return MetadataLookupServiceImpl(
        metadataService = metadataService,
        metadataProviders = listOf(AudibleMetadataProvider(metadataService)),
        coverSearchService =
            CoverSearchService(
                readBook = { null },
                registry = MetadataProviderRegistry(emptyList()),
                probeDimensions = { null },
            ),
        bookRepository = bookRepo,
        contributorRepository = contributorRepo,
        seriesRepository = seriesRepo,
        imageDeps =
            MetadataImageDeps(
                imageStorage = ImageStorage(HttpClient(MockEngine { _ -> respond("", HttpStatusCode.OK) })),
                coverImageStore =
                    CoverImageStore(ImageStore(Path(tempDir.resolve("covers").toString()), maxBytes = 10L * 1024 * 1024)),
                imageHome = Path(tempDir.toString()),
            ),
        enrichmentDeps = testEnrichmentDeps(dbs.sql, bus, registry),
        permissionPolicy = UserPermissionPolicy(dbs.sql),
        sqlDb = dbs.sql,
        genreRepository = genreRepo,
    )
}

private class EmptyAudibleApi : AudibleApi {
    override suspend fun search(
        region: AudibleRegion,
        params: SearchParams,
    ): AppResult<List<AudibleSearchResult>> = AppResult.Success(emptyList())

    override suspend fun getBook(
        region: AudibleRegion,
        asin: String,
    ): AppResult<AudibleBook?> = AppResult.Success(null)

    override suspend fun getChapters(
        region: AudibleRegion,
        asin: String,
    ): AppResult<List<AudibleChapter>> = AppResult.Success(emptyList())

    override suspend fun getContributor(
        region: AudibleRegion,
        asin: String,
    ): AppResult<AudibleContributorProfile?> = AppResult.Success(null)

    override suspend fun searchContributors(
        region: AudibleRegion,
        name: String,
    ): AppResult<List<AudibleContributorProfile>> = AppResult.Success(emptyList())

    override suspend fun getProductTags(
        region: AudibleRegion,
        asin: String,
    ): AppResult<List<ProductTag>> = AppResult.Success(emptyList())
}

private class NoOpITunesApiForPerm : ITunesApi {
    override suspend fun findCover(
        title: String,
        author: String,
    ): AppResult<ITunesCoverHit?> = AppResult.Success(null)

    override suspend fun searchCovers(
        title: String,
        author: String,
    ): AppResult<List<ITunesCoverHit>> = AppResult.Success(emptyList())
}
