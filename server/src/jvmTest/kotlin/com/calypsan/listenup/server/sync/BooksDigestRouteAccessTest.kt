package com.calypsan.listenup.server.sync

import com.calypsan.listenup.api.contractJson
import com.calypsan.listenup.api.dto.SharePermission
import com.calypsan.listenup.api.dto.auth.AuthSession
import com.calypsan.listenup.api.dto.auth.RegisterRequest
import com.calypsan.listenup.api.dto.auth.RegisterResult
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.sync.BookAudioFilePayload
import com.calypsan.listenup.api.sync.BookChapterPayload
import com.calypsan.listenup.api.sync.BookSyncPayload
import com.calypsan.listenup.api.sync.CollectionBookSyncPayload
import com.calypsan.listenup.api.sync.CollectionShareSyncPayload
import com.calypsan.listenup.api.sync.CollectionSyncPayload
import com.calypsan.listenup.api.sync.DomainDigest
import com.calypsan.listenup.core.FolderId
import com.calypsan.listenup.core.LibraryId
import com.calypsan.listenup.server.api.CollectionServiceImpl
import com.calypsan.listenup.server.api.SystemCollectionType
import com.calypsan.listenup.server.module
import com.calypsan.listenup.server.services.BookRepository
import com.calypsan.listenup.server.testing.seedTestBook
import com.calypsan.listenup.server.testing.seedTestLibraryAndFolder
import com.calypsan.listenup.server.testing.useIsolatedTestConfig
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import java.nio.file.Files
import com.calypsan.listenup.server.db.sqldelight.ListenUpDatabase
import org.koin.ktor.ext.inject

/**
 * Tier-2 route-level proof that `GET /api/v1/sync/books/digest` is access-scoped:
 * a member's digest folds only the books they may see, while an admin's covers every
 * live book. The hidden book lives in a stranger-owned private collection — denied to
 * the member until a share is granted, after which the member's recomputed digest
 * converges to include it.
 *
 * Sibling to [FirehoseBookAccessTest] (which gates the *live* tail); this guards the
 * *digest* drift-detection surface so the two never disagree on what a viewer can see.
 */
class BooksDigestRouteAccessTest :
    FunSpec({

        test("books digest route is access-scoped; share grant changes the member's digest") {
            val libraryRoot = Files.createTempDirectory("listenup-digest-access-")
            try {
                testApplication {
                    useIsolatedTestConfig(libraryPath = libraryRoot.toString())
                    application { module() }
                    val client = jsonClient()

                    val adminToken = client.mintRootToken()
                    val member = client.registerMember()
                    seedTestLibraryAndFolder()

                    val sql by application.inject<ListenUpDatabase>()
                    val books by application.inject<BookRepository>()
                    val collections by application.inject<CollectionRepository>()
                    val memberships by application.inject<CollectionBookRepository>()
                    val shares by application.inject<CollectionGrantRepository>()
                    val collectionService by application.inject<CollectionServiceImpl>()

                    // public-book is visible the pure-union way: it joins the per-library ALL_BOOKS
                    // system collection and the member holds a live USER grant on it. (The member
                    // registered before the library existed, so the registration-time default grant
                    // was skipped — mirror production by granting it explicitly here.)
                    // private-book lives only in a stranger-owned private collection → denied
                    // to the member, visible to the admin. Seed the parent row first to satisfy
                    // the collection_books FK before the membership upsert.
                    sql.seedTestBook("private-book")
                    books.upsert(bookSyncFixture(id = "public-book", title = "Open"))
                    books.upsert(bookSyncFixture(id = "private-book", title = "Secret"))
                    collections.upsert(collectionFixture("private-col", owner = "stranger"))
                    memberships.upsert(membership("private-col", "private-book"))

                    val allBooks = collectionService.getOrCreateSystemCollection("test-library", SystemCollectionType.ALL_BOOKS)
                    require(allBooks is AppResult.Success)
                    val allBooksId = allBooks.data.id.value
                    memberships.upsert(membership(allBooksId, "public-book"))
                    shares.upsert(allBooksGrant(allBooksId, member.userId))

                    // A cursor past every revision so the digest folds the whole domain.
                    val cursor = 1_000_000L

                    val adminDigest = client.booksDigest(adminToken, cursor)
                    val memberDigest = client.booksDigest(member.token, cursor)

                    // The admin sees both books; the member sees only the public one.
                    adminDigest.count shouldBe 2
                    memberDigest.count shouldBe 1
                    // Different row-sets → the fingerprints must diverge. If they matched, the
                    // gate isn't being exercised (the assertion below is the regression guard).
                    memberDigest.hash shouldNotBe adminDigest.hash

                    // Grant the member a live share on the private collection, then recompute.
                    shares.upsert(shareFixture("private-col", member.userId))
                    val memberDigestAfterShare = client.booksDigest(member.token, cursor)

                    // The member's digest now converges on the admin's: both books are visible.
                    memberDigestAfterShare.count shouldBe 2
                    memberDigestAfterShare.hash shouldBe adminDigest.hash
                    memberDigestAfterShare.hash shouldNotBe memberDigest.hash
                }
            } finally {
                libraryRoot.toFile().deleteRecursively()
            }
        }
    })

private fun ApplicationTestBuilder.jsonClient(): HttpClient = createClient { install(ContentNegotiation) { json(contractJson) } }

private suspend fun HttpClient.booksDigest(
    token: String,
    cursor: Long,
): DomainDigest = get("/api/v1/sync/books/digest?cursor=$cursor") { bearerAuth(token) }.body()

/** A registered member: their access token and server-issued user id. */
private data class Member(
    val token: String,
    val userId: String,
)

/** Runs first-user setup and returns the ROOT access token. */
private suspend fun HttpClient.mintRootToken(): String {
    val response =
        post("/api/v1/auth/setup") {
            contentType(ContentType.Application.Json)
            setBody(RegisterRequest("root@x", "x".repeat(8), "Root"))
        }
    return response
        .body<AppResult<AuthSession>>()
        .let { it as AppResult.Success<AuthSession> }
        .data
        .accessToken
        .value
}

/** Registers a second user (MEMBER role under OPEN policy) and returns their token + id. */
private suspend fun HttpClient.registerMember(): Member {
    val response =
        post("/api/v1/auth/register") {
            contentType(ContentType.Application.Json)
            setBody(RegisterRequest("member@x", "y".repeat(8), "Member"))
        }
    val session =
        response
            .body<AppResult<RegisterResult>>()
            .let { it as AppResult.Success<RegisterResult> }
            .data
            .let { it as RegisterResult.Authenticated }
            .session
    return Member(token = session.accessToken.value, userId = session.user.id.value)
}

private fun collectionFixture(
    id: String,
    owner: String,
): CollectionSyncPayload =
    CollectionSyncPayload(
        id = id,
        libraryId = "test-library",
        ownerId = owner,
        name = id,
        isInbox = false,
        revision = 0L,
        updatedAt = 0L,
    )

private fun membership(
    collectionId: String,
    bookId: String,
): CollectionBookSyncPayload =
    CollectionBookSyncPayload(
        id = "${collectionId}:${bookId}",
        collectionId = collectionId,
        bookId = bookId,
        createdAt = 0L,
        revision = 0L,
    )

private fun shareFixture(
    collectionId: String,
    sharedWithUserId: String,
): CollectionShareSyncPayload =
    CollectionShareSyncPayload(
        id = "share-$collectionId-$sharedWithUserId",
        collectionId = collectionId,
        sharedWithUserId = sharedWithUserId,
        sharedByUserId = "stranger",
        permission = SharePermission.Read,
        revision = 0L,
        updatedAt = 0L,
    )

/**
 * The member's default read-grant on the per-library `ALL_BOOKS` system collection — the public
 * substrate every member holds at registration in production. Issued by the system owner.
 */
private fun allBooksGrant(
    allBooksId: String,
    memberId: String,
): CollectionShareSyncPayload =
    CollectionShareSyncPayload(
        id = "all-books-grant-$memberId",
        collectionId = allBooksId,
        sharedWithUserId = memberId,
        sharedByUserId = "system",
        permission = SharePermission.Read,
        revision = 0L,
        updatedAt = 0L,
    )

private fun bookSyncFixture(
    id: String,
    title: String,
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
        contributors = emptyList(),
        series = emptyList(),
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
