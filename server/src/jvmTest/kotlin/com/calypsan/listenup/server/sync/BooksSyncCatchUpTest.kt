package com.calypsan.listenup.server.sync

import com.calypsan.listenup.server.testing.publicAuthService

import io.ktor.server.testing.ApplicationTestBuilder

import com.calypsan.listenup.api.SyncStreamService
import com.calypsan.listenup.api.dto.auth.AuthSession
import com.calypsan.listenup.api.dto.auth.LoginRequest
import com.calypsan.listenup.api.dto.auth.RegisterRequest
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.sync.BookAudioFilePayload
import com.calypsan.listenup.api.sync.BookChapterPayload
import com.calypsan.listenup.api.sync.BookContributorPayload
import com.calypsan.listenup.api.sync.BookSeriesPayload
import com.calypsan.listenup.api.sync.BookSyncPayload
import com.calypsan.listenup.core.FolderId
import com.calypsan.listenup.core.LibraryId
import com.calypsan.listenup.server.module
import com.calypsan.listenup.server.services.BookRepository
import com.calypsan.listenup.server.services.ContributorRepository
import com.calypsan.listenup.server.services.SeriesRepository
import com.calypsan.listenup.server.testing.authedService
import com.calypsan.listenup.server.testing.rows
import com.calypsan.listenup.server.testing.seedTestLibraryAndFolder
import com.calypsan.listenup.server.testing.shouldSucceed
import com.calypsan.listenup.server.testing.useIsolatedTestConfig
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.ktor.server.testing.testApplication
import org.koin.ktor.ext.inject
import java.nio.file.Files

/**
 * Tier-2 integration tests: Books catch-up pagination via
 * [SyncStreamService.pullDomain] on the `books` domain.
 *
 * Verifies that the Books domain's [BookRepository] correctly paginates
 * through the sync catch-up call: page-1 returns `limit` items with
 * `hasMore = true`; following `nextCursor` returns the remainder with
 * `hasMore = false`. Round-trips through real wire serialization
 * ([BookSyncPayload] rows inside the returned `SyncPage`).
 *
 * Boots the full [module] (same approach as [com.calypsan.listenup.server.routes.BookRoutesTest])
 * with a temp library path so `booksModule` is installed and [BookRepository]
 * self-registers with the [SyncRegistry]. The sync RPC surface is auth-gated in
 * `Application.module()`, so calls carry a JWT minted via the real auth
 * REST surface.
 */
class BooksSyncCatchUpTest :
    FunSpec({

        test("catch-up first page has 100 items and hasMore=true when 150 books exist") {
            val libraryRoot = Files.createTempDirectory("listenup-books-catchup-test-")
            try {
                testApplication {
                    useIsolatedTestConfig(libraryPath = libraryRoot.toString())
                    application { module() }

                    val token = mintAccessToken()
                    seedTestLibraryAndFolder()

                    val repo by application.inject<BookRepository>()
                    repeat(150) { i -> repo.upsert(bookSyncFixture(id = "book-$i", title = "Book $i")) }

                    val page =
                        authedService<SyncStreamService>(token)
                            .pullDomain("books", since = 0, limit = 100)
                            .shouldSucceed()
                    val items = page.rows(BookSyncPayload.serializer())

                    items shouldHaveSize 100
                    page.hasMore.shouldBeTrue()
                    page.nextCursor shouldNotBe null
                }
            } finally {
                libraryRoot.toFile().deleteRecursively()
            }
        }

        test("catch-up second page returns remaining 50 items and hasMore=false") {
            val libraryRoot = Files.createTempDirectory("listenup-books-catchup-page2-")
            try {
                testApplication {
                    useIsolatedTestConfig(libraryPath = libraryRoot.toString())
                    application { module() }

                    val token = mintAccessToken()
                    seedTestLibraryAndFolder()

                    val repo by application.inject<BookRepository>()
                    repeat(150) { i -> repo.upsert(bookSyncFixture(id = "book-$i", title = "Book $i")) }

                    val sync = authedService<SyncStreamService>(token)

                    // Page 1
                    val page1 = sync.pullDomain("books", since = 0, limit = 100).shouldSucceed()
                    val page1Items = page1.rows(BookSyncPayload.serializer())
                    page1Items shouldHaveSize 100
                    page1.hasMore.shouldBeTrue()
                    val cursor = page1.nextCursor!!

                    // Page 2 — follow cursor
                    val page2 = sync.pullDomain("books", since = cursor, limit = 100).shouldSucceed()
                    val page2Items = page2.rows(BookSyncPayload.serializer())
                    page2Items shouldHaveSize 50
                    page2.hasMore.shouldBeFalse()

                    // Both pages together cover all 150 books
                    val allIds = (page1Items + page2Items).map { it.id }.toSet()
                    allIds.size shouldBe 150
                }
            } finally {
                libraryRoot.toFile().deleteRecursively()
            }
        }

        test("books payloads round-trip through wire serialization intact") {
            val libraryRoot = Files.createTempDirectory("listenup-books-catchup-rt-")
            try {
                testApplication {
                    useIsolatedTestConfig(libraryPath = libraryRoot.toString())
                    application { module() }

                    val token = mintAccessToken()
                    seedTestLibraryAndFolder()

                    val repo by application.inject<BookRepository>()
                    val contributors by application.inject<ContributorRepository>()
                    val series by application.inject<SeriesRepository>()
                    // Resolve real catalogue ids — junction-row FKs require them.
                    val contributorId = contributors.resolveOrCreate("Brandon Sanderson", sortName = null).value
                    val seriesId = series.resolveOrCreate("Stormlight Archive").value
                    repo.upsert(
                        bookSyncFixture(
                            id = "rt-book",
                            title = "Round-trip Title",
                            contributorId = contributorId,
                            seriesId = seriesId,
                        ),
                    )

                    // Old REST route defaulted to limit=500 when omitted; pass it explicitly to
                    // preserve that paging behaviour now that limit is a required RPC argument.
                    val page =
                        authedService<SyncStreamService>(token)
                            .pullDomain("books", since = 0, limit = 500)
                            .shouldSucceed()
                    val items = page.rows(BookSyncPayload.serializer())

                    items shouldHaveSize 1
                    val book = items.first()
                    book.id shouldBe "rt-book"
                    book.title shouldBe "Round-trip Title"
                    book.contributors shouldHaveSize 1
                    book.contributors.first().name shouldBe "Brandon Sanderson"
                    book.audioFiles shouldHaveSize 1
                    book.chapters shouldHaveSize 1
                }
            } finally {
                libraryRoot.toFile().deleteRecursively()
            }
        }
    })

private suspend fun ApplicationTestBuilder.mintAccessToken(): String {
    publicAuthService().setupRoot(RegisterRequest("root@x", "x".repeat(8), "Root"))
    val response =
        publicAuthService().login(LoginRequest("root@x", "x".repeat(8)))
    return response
        .let { it as AppResult.Success<AuthSession> }
        .data
        .accessToken
        .value
}

/**
 * Builds a [BookSyncPayload] fixture.
 *
 * [contributorId] / [seriesId] default to null — contributors/series are then
 * left empty. Junction-row writes require ids that already exist in the
 * catalogue tables (see `BookRepository.replaceContributors`); a test that
 * asserts on contributors/series resolves real ids through the catalogue
 * repositories first and passes them here.
 */
private fun bookSyncFixture(
    id: String,
    title: String,
    contributorId: String? = null,
    seriesId: String? = null,
): BookSyncPayload =
    BookSyncPayload(
        id = id,
        libraryId = LibraryId("test-library"),
        folderId = FolderId("test-folder"),
        title = title,
        sortTitle = title,
        subtitle = null,
        description = null,
        publishYear = null,
        publisher = null,
        language = null,
        isbn = null,
        asin = null,
        abridged = false,
        explicit = false,
        totalDuration = 3_600_000L,
        cover = null,
        rootRelPath = "books/$id",
        inode = null,
        scannedAt = 1_730_000_000_000L,
        contributors =
            contributorId?.let {
                listOf(
                    BookContributorPayload(
                        id = it,
                        name = "Brandon Sanderson",
                        sortName = "Sanderson, Brandon",
                        role = "author",
                        creditedAs = null,
                    ),
                )
            } ?: emptyList(),
        series =
            seriesId?.let {
                listOf(BookSeriesPayload(id = it, name = "Stormlight Archive", sequence = 1.0))
            } ?: emptyList(),
        audioFiles =
            listOf(
                BookAudioFilePayload(
                    id = "af-$id",
                    index = 0,
                    filename = "01.m4b",
                    format = "m4b",
                    codec = "aac",
                    duration = 3_600_000L,
                    size = 500_000_000L,
                ),
            ),
        chapters =
            listOf(
                BookChapterPayload(id = "ch-$id", title = "Prologue", duration = 1_000_000L, startTime = 0L),
            ),
        revision = 0L,
        updatedAt = 0L,
        createdAt = 0L,
        deletedAt = null,
    )
