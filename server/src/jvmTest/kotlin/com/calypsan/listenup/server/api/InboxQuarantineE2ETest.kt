package com.calypsan.listenup.server.api

import com.calypsan.listenup.api.contractJson
import com.calypsan.listenup.api.dto.auth.AuthSession
import com.calypsan.listenup.api.dto.auth.RegisterRequest
import com.calypsan.listenup.api.dto.auth.RegisterResult
import com.calypsan.listenup.api.dto.auth.SessionId
import com.calypsan.listenup.api.dto.auth.UserId
import com.calypsan.listenup.api.dto.auth.UserRole
import com.calypsan.listenup.api.dto.scanner.AnalyzedBook
import com.calypsan.listenup.api.dto.scanner.CandidateBook
import com.calypsan.listenup.api.dto.scanner.ChangeEventDto
import com.calypsan.listenup.api.dto.scanner.FileEntry
import com.calypsan.listenup.api.dto.scanner.FileType
import com.calypsan.listenup.api.dto.scanner.ScanResult
import com.calypsan.listenup.api.dto.scanner.ScanScope
import com.calypsan.listenup.api.dto.scanner.TrackEntry
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.sync.BookAudioFilePayload
import com.calypsan.listenup.api.sync.BookChapterPayload
import com.calypsan.listenup.api.sync.BookSyncPayload
import com.calypsan.listenup.api.sync.Page
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.FolderId
import com.calypsan.listenup.core.LibraryId
import com.calypsan.listenup.server.auth.PrincipalProvider
import com.calypsan.listenup.server.auth.UserPrincipal
import com.calypsan.listenup.server.db.sqldelight.ListenUpDatabase
import com.calypsan.listenup.server.module
import com.calypsan.listenup.server.services.BookPersister
import com.calypsan.listenup.server.services.BookRepository
import com.calypsan.listenup.server.services.LibraryRegistry
import com.calypsan.listenup.server.sync.ChangeBus
import com.calypsan.listenup.server.testing.domainFrames
import com.calypsan.listenup.server.testing.memberPrincipal
import com.calypsan.listenup.server.testing.rpcFirehose
import com.calypsan.listenup.server.testing.useIsolatedTestConfig
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import java.nio.file.Files
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.flow.first
import org.koin.ktor.ext.inject

/**
 * The crown-jewel auto-quarantine proof.
 *
 * When a library is `inboxEnabled`, the scan path ([BookPersister]) must resolve the
 * library's inbox once per scan and thread its id into the ingest so that — atomically
 * with the book-insert transaction — a newly-scanned book lands in the admin-only inbox.
 * The `collection_books` membership is written by the SQLDelight `collectionBooksQueries`
 * inside the same transaction as the book row, so the book is collected the instant it
 * exists. The firehose evaluates [BookAccessPolicy.canAccess] at delivery, so atomic
 * membership means a member never sees the book: not through the live firehose, not through
 * `getBook`, and not through a REST catch-up pull (there is no window in which the book is
 * uncollected-and-therefore-public). An admin sees it in the inbox, and `releaseBooks` makes
 * it visible to the member.
 *
 * This test drives the *real* singleton [BookPersister] (the production scan consumer)
 * inside a full [module] — real Koin graph, real DB, real RPC firehose, real
 * [CollectionServiceImpl] inbox resolution. It is **load-bearing**: the quarantine invariant
 * holds purely by atomicity (the membership shares the book's transaction), with no firehose
 * suppression involved — so were the membership ever moved back to a separate post-commit
 * transaction, this test would catch the regression because the member's firehose would then
 * receive the momentarily-public `book.Created`.
 *
 * Book ids are minted UUIDs, so the test resolves title→id via the admin's catch-up page
 * (the admin sees every book) and keys all assertions off that resolved id.
 *
 * Two scenarios:
 *  1. inboxEnabled=true  → member never sees a scanned book (firehose + getBook); admin's
 *     `listInbox` contains it; `releaseBooks` converges it to the member; a re-scan does
 *     not re-inbox a released book.
 *  2. inboxEnabled=false → member sees the scanned book immediately (control).
 */
class InboxQuarantineE2ETest :
    FunSpec({

        test("inbox-enabled library: a scanned book is quarantined from members, visible to the admin, releasable")
            .config(timeout = 2.minutes) {
                val libraryRoot = Files.createTempDirectory("listenup-inbox-quarantine-")
                try {
                    testApplication {
                        useIsolatedTestConfig(libraryPath = libraryRoot.toString())
                        application { module() }
                        val client = jsonClient()

                        val admin = client.runSetup()
                        val m1 = client.registerMember("m1")

                        // The default library is bootstrapped at libraryRoot; resolve the id the
                        // scan path actually uses (LibraryRegistry.currentLibrary), then enable its
                        // inbox gate. Using the bootstrap library — not a separately-seeded one —
                        // is essential: the persister scans into currentLibrary(), so the gate must
                        // sit on that exact library.
                        val registry by application.inject<LibraryRegistry>()
                        val libraryId = registry.currentLibrary().value

                        // Turn on the per-library inbox gate — the toggle Task 1 exposes via
                        // LibraryAdminService.setInboxEnabled; set directly here so this test
                        // isolates the scan-path wiring (the admin RPC is covered elsewhere).
                        val sqlDb by application.inject<ListenUpDatabase>()
                        sqlDb.setInboxEnabled(libraryId, enabled = true)

                        val persister by application.inject<BookPersister>()
                        val books by application.inject<BookRepository>()
                        val collections = collectionServiceAs(admin.userId, UserRole.ADMIN)

                        // ── Firehose: a SUBTREE scan publishes live deltas (a FULL scan suppresses
                        // them). We scan a NEW book `Quarantined`; the member's firehose must NEVER
                        // deliver a `books` content event for it — because membership commits
                        // atomically with the insert, so canAccess at delivery already sees it in
                        // the admin-only inbox. The visible control: we upsert a PUBLIC book
                        // `Public` and add it to ALL_BOOKS; the member's firehose DOES carry that
                        // one, proving the stream is alive and not leaking. The bus replays data
                        // frames in publish order, so mutate-then-collect is deterministic: the
                        // FIRST `books` frame the member sees must be `Public`, never `Quarantined`
                        // — if membership were committed in a separate transaction, the quarantined
                        // book's momentarily-public event would deliver first and fail this.
                        // Resolve ALL_BOOKS up front: the visible control `Public` joins it so the
                        // member (who holds a default ALL_BOOKS grant) can see it under pure union.
                        val allBooksId =
                            (
                                collections.getOrCreateSystemCollection(libraryId, SystemCollectionType.ALL_BOOKS)
                                    as AppResult.Success
                            ).data.id

                        // Scan the quarantined book — its content event must be dropped for m1.
                        persister.scanSubtree(libraryRoot.toString(), book("Quarantined"))
                        // The visible public control — m1 must receive this one. We upsert the
                        // book then add it to ALL_BOOKS (both awaited, so the membership commits
                        // before the member's delivery-time canAccess probe pulls the event):
                        // under pure union a book is visible to a granted member only via a
                        // reachable collection, and ALL_BOOKS is the public substrate.
                        books.upsert(publicBook("Public", libraryId)).requireSuccess()
                        collections.addBookToCollection(allBooksId, BookId("public-Public")).requireSuccess()

                        val bus by application.inject<ChangeBus>()
                        val policy by application.inject<BookAccessPolicy>()
                        val firstBooksFrame =
                            rpcFirehose(bus, memberPrincipal(m1.userId), bookAccessPolicy = { policy })
                                .domainFrames()
                                .first { it.domain == "books" }
                        // Assert on the title (a stable known value) since book ids are minted UUIDs.
                        firstBooksFrame.json.contains(""""title":"Public"""") shouldBe true
                        firstBooksFrame.json.contains(""""title":"Quarantined"""") shouldBe false

                        // Resolve the minted ids the admin can see (the admin sees every book).
                        val quarantinedId = client.findBookIdByTitle(admin.token, "Quarantined")

                        // ── getBook: the member is denied the quarantined book (NotFound — the deny
                        // is indistinguishable from absent). The admin's inbox list contains it.
                        client.getBook(m1.token, quarantinedId).status shouldBe HttpStatusCode.NotFound
                        val inboxIds = (collections.listInbox(libraryId) as AppResult.Success).data.map { it.value }
                        inboxIds shouldContain quarantinedId

                        // ── Release the quarantined book with no explicit target → it joins ALL_BOOKS
                        // (the public substrate). The member (default ALL_BOOKS grant) now sees it.
                        collections
                            .releaseBooks(libraryId, mapOf(quarantinedId to emptyList()))
                            .requireSuccess()
                        client.getBook(m1.token, quarantinedId).status shouldBe HttpStatusCode.OK

                        // ── Re-scan the now-released book: only-on-create means it must NOT be re-inboxed.
                        persister.scanSubtree(libraryRoot.toString(), book("Quarantined"))
                        val inboxAfterRescan =
                            (collections.listInbox(libraryId) as AppResult.Success).data.map { it.value }
                        inboxAfterRescan shouldNotContain quarantinedId
                        client.getBook(m1.token, quarantinedId).status shouldBe HttpStatusCode.OK
                    }
                } finally {
                    libraryRoot.toFile().deleteRecursively()
                }
            }

        test("inbox-disabled library: a scanned book is immediately visible to members") {
            val libraryRoot = Files.createTempDirectory("listenup-inbox-disabled-")
            try {
                testApplication {
                    useIsolatedTestConfig(libraryPath = libraryRoot.toString())
                    application { module() }
                    val client = jsonClient()

                    val admin = client.runSetup()
                    val m1 = client.registerMember("m1")
                    // The bootstrap library at libraryRoot is the only library; inboxEnabled stays
                    // false (the default) — no quarantine.

                    val persister by application.inject<BookPersister>()
                    persister.scanSubtree(libraryRoot.toString(), book("Open"))

                    // In ALL_BOOKS: the member sees it through getBook immediately, no release step needed.
                    val openId = client.findBookIdByTitle(admin.token, "Open")
                    client.getBook(m1.token, openId).status shouldBe HttpStatusCode.OK
                }
            } finally {
                libraryRoot.toFile().deleteRecursively()
            }
        }
    })

// ── Scan driving: feed the real BookPersister a Subtree ScanResult ──

/**
 * Drives one book through the real singleton [BookPersister] as a [ScanScope.Subtree]
 * scan — incremental scans publish live firehose deltas (full scans suppress them), so
 * this exercises the firehose path the quarantine guarantee protects. [rootPath] must
 * match the seeded folder's `rootPath` so the persister resolves the folder id.
 */
private suspend fun BookPersister.scanSubtree(
    rootPath: String,
    book: AnalyzedBook,
) {
    persist(
        ScanResult(
            correlationId = "scan-${book.candidate.rootRelPath}",
            rootPath = rootPath,
            books = listOf(book),
            // Represent the book as Added so the persister processes it.
            // A real Scanner would populate changes from the Differ; tests drive it directly.
            changes = listOf(ChangeEventDto.Added(book)),
            errors = emptyList(),
            durationMs = 0L,
            filesWalked = 1,
            filesSkipped = 0,
            scope = ScanScope.Subtree(rootRelPath = book.candidate.rootRelPath),
        ),
    )
}

/** An [AnalyzedBook] with [title] as both its title and root-relative path. */
private fun book(title: String): AnalyzedBook {
    val file =
        FileEntry(
            relPath = "$title/01.m4b",
            name = "01.m4b",
            ext = "m4b",
            size = 1024L,
            mtimeMs = 0L,
            inode = null,
            fileType = FileType.AUDIO,
        )
    return AnalyzedBook(
        candidate = CandidateBook(rootRelPath = title, isFile = false, files = listOf(file)),
        title = title,
        tracks = listOf(TrackEntry(file = file)),
    )
}

/**
 * A minimal [BookSyncPayload] used as the live firehose control: a member must receive its
 * content event, proving the stream is alive while the quarantined book is filtered. The
 * caller adds it to ALL_BOOKS so a granted member can see it under the pure-union rule.
 */
private fun publicBook(
    title: String,
    libraryId: String,
): BookSyncPayload =
    BookSyncPayload(
        id = "public-$title",
        libraryId = LibraryId(libraryId),
        folderId = FolderId("unknown"),
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
        rootRelPath = "books/$title",
        inode = null,
        scannedAt = 1_730_000_000_000L,
        contributors = emptyList(),
        series = emptyList(),
        audioFiles =
            listOf(
                BookAudioFilePayload(
                    id = "af-$title",
                    index = 0,
                    filename = "01.m4b",
                    format = "m4b",
                    codec = "aac",
                    duration = 3_600_000L,
                    size = 256L,
                ),
            ),
        chapters =
            listOf(
                BookChapterPayload(id = "ch-$title", title = "Prologue", duration = 1_000_000L, startTime = 0L),
            ),
        revision = 0L,
        updatedAt = 0L,
        createdAt = 0L,
        deletedAt = null,
    )

private fun ListenUpDatabase.setInboxEnabled(
    libraryId: String,
    enabled: Boolean,
) {
    librariesQueries.setInboxEnabled(
        inbox_enabled = if (enabled) 1L else 0L,
        revision = 1L,
        updated_at = System.currentTimeMillis(),
        client_op_id = null,
        id = libraryId,
    )
}

// ── Multi-user token setup (mirrors SeamLeakE2ETest) ──

private data class QuarantineUser(
    val token: String,
    val userId: String,
)

private fun ApplicationTestBuilder.jsonClient(): HttpClient =
    createClient {
        install(ContentNegotiation) { json(contractJson) }
    }

private suspend fun HttpClient.runSetup(): QuarantineUser {
    val session =
        post("/api/v1/auth/setup") {
            contentType(ContentType.Application.Json)
            setBody(RegisterRequest("root@x", "x".repeat(8), "Root"))
        }.body<AppResult<AuthSession>>()
            .let { it as AppResult.Success<AuthSession> }
            .data
    return QuarantineUser(token = session.accessToken.value, userId = session.user.id.value)
}

private suspend fun HttpClient.registerMember(name: String): QuarantineUser {
    val session =
        post("/api/v1/auth/register") {
            contentType(ContentType.Application.Json)
            setBody(RegisterRequest("$name@x", "y".repeat(8), name))
        }.body<AppResult<RegisterResult>>()
            .let { it as AppResult.Success<RegisterResult> }
            .data
            .let { it as RegisterResult.Authenticated }
            .session
    return QuarantineUser(token = session.accessToken.value, userId = session.user.id.value)
}

private suspend fun HttpClient.getBook(
    token: String,
    bookId: String,
): HttpResponse = get("/api/v1/books/$bookId") { bearerAuth(token) }

/** Resolves a book's minted id by its (unique-in-test) title via the admin catch-up page. */
private suspend fun HttpClient.findBookIdByTitle(
    adminToken: String,
    title: String,
): String {
    val page: Page<BookSyncPayload> =
        get("/api/v1/sync/books?since=0&limit=1000") { bearerAuth(adminToken) }.body()
    return page.items.first { it.title == title }.id
}

// ── CollectionService driving (real singleton, shares the firehose bus) ──

private fun ApplicationTestBuilder.collectionServiceAs(
    userId: String,
    role: UserRole,
): CollectionServiceImpl {
    val service by application.inject<CollectionServiceImpl>()
    return service.copyWith(
        PrincipalProvider {
            UserPrincipal(UserId(userId), SessionId("session-$userId"), role)
        },
    )
}

private suspend fun <T> AppResult<T>.requireSuccess(): T {
    require(this is AppResult.Success) { "expected Success but got $this" }
    return data
}
