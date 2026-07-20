@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.calypsan.listenup.server.api

import com.calypsan.listenup.api.contractJson
import com.calypsan.listenup.api.dto.SearchResults
import com.calypsan.listenup.api.dto.SharePermission
import com.calypsan.listenup.api.dto.auth.AuthSession
import com.calypsan.listenup.api.dto.auth.RegisterRequest
import com.calypsan.listenup.api.dto.auth.RegisterResult
import com.calypsan.listenup.api.dto.auth.SessionId
import com.calypsan.listenup.api.dto.auth.UserId
import com.calypsan.listenup.api.dto.auth.UserRole
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.sync.BookAudioFilePayload
import com.calypsan.listenup.api.sync.BookChapterPayload
import com.calypsan.listenup.api.sync.BookSyncPayload
import com.calypsan.listenup.api.sync.CollectionBookSyncPayload
import com.calypsan.listenup.api.sync.CoverPayload
import com.calypsan.listenup.api.sync.CoverSource
import com.calypsan.listenup.api.sync.DomainDigest
import com.calypsan.listenup.api.sync.Page
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.CollectionId
import com.calypsan.listenup.core.FolderId
import com.calypsan.listenup.core.LibraryId
import com.calypsan.listenup.server.audio.AudioUrlSigner
import com.calypsan.listenup.server.auth.PrincipalProvider
import com.calypsan.listenup.server.auth.UserPrincipal
import com.calypsan.listenup.server.module
import com.calypsan.listenup.server.services.BookRepository
import com.calypsan.listenup.server.services.LibraryRegistry
import com.calypsan.listenup.server.sync.CollectionBookRepository
import com.calypsan.listenup.server.testing.seedTestLibraryAndFolder
import com.calypsan.listenup.server.testing.useIsolatedTestConfig
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.sse.SSE
import io.ktor.client.plugins.sse.sse
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
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import org.koin.ktor.ext.inject

/**
 * The Collections-1b deliverable: the adversarial seam-leak proof.
 *
 * A book in a private collection (or the inbox) must be unreachable to a member `m1`
 * through **all six** enforcement seams — and reachable to an admin through every seam.
 * Sharing the collection with `m2` converges access; revoking removes it; a
 * global-access collection is visible to all members.
 *
 * Tasks 2-9 each gated one seam in isolation; this test combines them into one
 * multi-user scenario hitting the *real* HTTP routes / SSE firehose of a full
 * [module]. Every seam assertion carries a **visible control** — a public book `P`
 * (or, for sharing, the admin's view) that `m1` *does* see — so the assertion fails
 * if the gate is removed, never because the query trivially returned nothing.
 *
 * The six seams (each `// SEAM n`):
 *  1. getBook       — `GET /api/v1/books/{id}` → NotFound on deny
 *  2. search        — `GET /api/v1/search` → B absent from results AND facet counts
 *  3. audio         — `GET /api/v1/audio/{book}/{file}?…` → validly-signed URL still 404s
 *  4. catch-up      — `GET /api/v1/sync/books?since=0` → B absent from the page
 *  5. digest        — `GET /api/v1/sync/books/digest?cursor=…` → B uncounted (vs admin)
 *  6. firehose      — `GET /api/v1/sync/events` → a live content event for B never arrives
 */
class SeamLeakE2ETest :
    FunSpec({

        // Six seams + a full SSE round-trip in one body: bump the per-test invocation timeout
        // above Kotest's 1m default so a cold/loaded CI run can't false-fail this security proof.
        test("private/inbox book leaks through NONE of the six seams for a member").config(timeout = 2.minutes) {
            val libraryRoot = Files.createTempDirectory("listenup-seamleak-member-")
            try {
                testApplication {
                    useIsolatedTestConfig(libraryPath = libraryRoot.toString())
                    application { module() }
                    val client = sseJsonClient()

                    // ── Seed: admin `a`, member `m1` (no access), books on disk + FTS ──
                    val admin = client.runSetup()
                    val m1 = client.registerMember("m1")
                    seedTestLibraryAndFolder(folderPath = libraryRoot.toString())
                    writeAudioFile(libraryRoot, "B")
                    writeAudioFile(libraryRoot, "B_inbox")
                    writeAudioFile(libraryRoot, "P")
                    // B and the public control P each carry a filesystem cover so SEAM 7 has a
                    // 200-vs-404 control: m1 must be denied B's cover but served P's.
                    writeCoverFile(libraryRoot, "B")
                    writeCoverFile(libraryRoot, "P")

                    val books by application.inject<BookRepository>()
                    // "Dragon" is the shared FTS term: B, B_inbox, and the public control P
                    // all match the same query, so "P present, B absent" proves the filter.
                    books.upsert(bookFixture("B", "Dragon Secret", withCover = true))
                    books.upsert(bookFixture("B_inbox", "Dragon Inbox"))
                    books.upsert(bookFixture("P", "Dragon Public", withCover = true))

                    // B lives in a private collection owned by the admin; B_inbox in the inbox.
                    // Both reached through the real CollectionService, which shares the firehose bus.
                    val collections = collectionServiceAs(admin.userId, UserRole.ADMIN)
                    collections.createPrivateCollection("Private", "B")
                    collections.addToInbox("B_inbox", "test-library").requireSuccess()
                    // The public control P is public the pure-union way: in ALL_BOOKS, which m1
                    // reaches through their default ALL_BOOKS grant.
                    makeBookPublic("P")

                    // ─────────────────────────── SEAM 1: getBook ───────────────────────────
                    // Control: m1 fetches P (public) → 200. B / B_inbox → NotFound (indistinguishable
                    // from absent — a Forbidden would itself leak existence). If the gate were
                    // removed, getBook(B) would 200 like P does.
                    client.getBook(m1.token, "P").status shouldBe HttpStatusCode.OK
                    client.getBook(m1.token, "B").status shouldBe HttpStatusCode.NotFound
                    client.getBook(m1.token, "B_inbox").status shouldBe HttpStatusCode.NotFound

                    // ─────────────────────────── SEAM 2: search ───────────────────────────
                    // Control: P appears in m1's results. B / B_inbox must be absent from BOTH
                    // the result rows AND the type-facet count (facets leak existence just as a
                    // row does). m1 sees exactly 1 "Dragon" book; the admin sees 3.
                    val m1Search = client.search(m1.token, "Dragon")
                    m1Search.books.map { it.id.value } shouldContain "P"
                    m1Search.books.map { it.id.value } shouldNotContain "B"
                    m1Search.books.map { it.id.value } shouldNotContain "B_inbox"
                    m1Search.facets.types.books shouldBe 1
                    // Cross-check the control is real: the admin's facet count proves 3 books match.
                    client
                        .search(admin.token, "Dragon")
                        .facets.types.books shouldBe 3

                    // ─────────────────────────── SEAM 3: audio ───────────────────────────
                    // A VALID HMAC signature for (m1, B, af-B) still 404s — the deny is an access
                    // decision, not a signature failure. Control: the same signing path for P serves
                    // bytes (200), so the 404 isn't a broken-signer artefact.
                    val signer = AudioUrlSigner(signingKey = AudioUrlSigner.deriveSigningKey("x".repeat(32)))
                    client.audio(signer.signedQuery(m1.userId, "P", "af-P"), "P", "af-P").status shouldBe
                        HttpStatusCode.OK
                    client.audio(signer.signedQuery(m1.userId, "B", "af-B"), "B", "af-B").status shouldBe
                        HttpStatusCode.NotFound
                    client.audio(signer.signedQuery(m1.userId, "B_inbox", "af-B_inbox"), "B_inbox", "af-B_inbox").status shouldBe
                        HttpStatusCode.NotFound

                    // ─────────────────────────── SEAM 4: catch-up ───────────────────────────
                    // Control: P is in m1's catch-up page; B / B_inbox are not. If the
                    // access fragment were dropped, the private book would replay to m1's Room.
                    val m1Page = client.catchUp(m1.token)
                    val m1Ids = m1Page.items.map { it.id }
                    m1Ids shouldContain "P"
                    m1Ids shouldNotContain "B"
                    m1Ids shouldNotContain "B_inbox"

                    // ─────────────────────────── SEAM 5: digest ───────────────────────────
                    // Control: m1's digest folds only P (count 1); the admin's folds all 3.
                    // Different row-sets → different fingerprints. Equal hashes would mean the
                    // gate isn't exercised — the divergence is the regression guard.
                    val m1Digest = client.digest(m1.token)
                    val adminDigest = client.digest(admin.token)
                    m1Digest.count shouldBe 1
                    adminDigest.count shouldBe 3
                    m1Digest.hash shouldNotBe adminDigest.hash

                    // ─────────────────────────── SEAM 7: cover ───────────────────────────
                    // Control: m1 fetches P's cover bytes (200). B's cover → NotFound — the
                    // denial is indistinguishable from a cover-less / absent book. If the route
                    // were ungated, m1 would receive B's artwork (book content) just like P's.
                    client.cover(m1.token, "P").status shouldBe HttpStatusCode.OK
                    client.cover(m1.token, "B").status shouldBe HttpStatusCode.NotFound

                    // ─────────────────────────── SEAM 6: firehose ───────────────────────────
                    // m1 subscribes; the server emits a live CONTENT event for B (private) then for
                    // P (public). The FIRST `books` event m1 sees must be P, never B — proving the
                    // private content event was dropped before send. (Tombstones are deliberately
                    // ungated, so we test a content Updated, not a delete.) The arrival of P is the
                    // visible control: the stream is alive and delivering, just not leaking B.
                    client.sse(
                        urlString = "/api/v1/sync/events",
                        request = { bearerAuth(m1.token) },
                    ) {
                        coroutineScope {
                            val firstBooksEvent = async { incoming.first { it.event == "books" } }
                            books.upsert(bookFixture("B", "Dragon Secret Updated"))
                            books.upsert(bookFixture("P", "Dragon Public Updated"))
                            val event = firstBooksEvent.await()

                            event.data!!.contains(""""id":"P"""") shouldBe true
                            event.data!!.contains(""""id":"B"""") shouldBe false
                        }
                    }
                }
            } finally {
                libraryRoot.toFile().deleteRecursively()
            }
        }

        test("admin reaches the private/inbox book through every seam") {
            val libraryRoot = Files.createTempDirectory("listenup-seamleak-admin-")
            try {
                testApplication {
                    useIsolatedTestConfig(libraryPath = libraryRoot.toString())
                    application { module() }
                    val client = sseJsonClient()

                    val admin = client.runSetup()
                    seedTestLibraryAndFolder(folderPath = libraryRoot.toString())
                    writeAudioFile(libraryRoot, "B")
                    writeAudioFile(libraryRoot, "B_inbox")
                    writeCoverFile(libraryRoot, "B")

                    val books by application.inject<BookRepository>()
                    books.upsert(bookFixture("B", "Dragon Secret", withCover = true))
                    books.upsert(bookFixture("B_inbox", "Dragon Inbox"))

                    val collections = collectionServiceAs(admin.userId, UserRole.ADMIN)
                    // Private collection owned by a STRANGER — the admin has no relationship to it,
                    // so a reachable result can only come from the ADMIN bypass, not ownership.
                    collections.createPrivateCollectionAs("stranger", "Private", "B")
                    collections.addToInbox("B_inbox", "test-library").requireSuccess()

                    // SEAM 1: getBook → 200 for both.
                    client.getBook(admin.token, "B").status shouldBe HttpStatusCode.OK
                    client.getBook(admin.token, "B_inbox").status shouldBe HttpStatusCode.OK

                    // SEAM 2: search → both private books counted + present.
                    val adminSearch = client.search(admin.token, "Dragon")
                    adminSearch.books.map { it.id.value } shouldContain "B"
                    adminSearch.books.map { it.id.value } shouldContain "B_inbox"
                    adminSearch.facets.types.books shouldBe 2

                    // SEAM 3: audio → validly-signed URL serves bytes.
                    val signer = AudioUrlSigner(signingKey = AudioUrlSigner.deriveSigningKey("x".repeat(32)))
                    client.audio(signer.signedQuery(admin.userId, "B", "af-B"), "B", "af-B").status shouldBe
                        HttpStatusCode.OK

                    // SEAM 4: catch-up → both private books replay.
                    val ids = client.catchUp(admin.token).items.map { it.id }
                    ids shouldContain "B"
                    ids shouldContain "B_inbox"

                    // SEAM 5: digest → folds all books (count 2).
                    client.digest(admin.token).count shouldBe 2

                    // SEAM 7: cover → the admin is served the private book's cover bytes.
                    client.cover(admin.token, "B").status shouldBe HttpStatusCode.OK

                    // SEAM 6: firehose → a live content event for the private B reaches the admin.
                    client.sse(
                        urlString = "/api/v1/sync/events",
                        request = { bearerAuth(admin.token) },
                    ) {
                        coroutineScope {
                            val deferred =
                                async { incoming.first { it.event == "books" && it.data!!.contains(""""id":"B"""") } }
                            books.upsert(bookFixture("B", "Dragon Secret Updated"))
                            deferred.await().data!!.contains(""""id":"B"""") shouldBe true
                        }
                    }
                }
            } finally {
                libraryRoot.toFile().deleteRecursively()
            }
        }

        test("sharing B's collection with m2 converges access; revoke removes it; an AccessChanged frame is delivered") {
            val libraryRoot = Files.createTempDirectory("listenup-seamleak-share-")
            try {
                testApplication {
                    useIsolatedTestConfig(libraryPath = libraryRoot.toString())
                    application { module() }
                    val client = sseJsonClient()

                    val admin = client.runSetup()
                    val m2 = client.registerMember("m2")
                    seedTestLibraryAndFolder(folderPath = libraryRoot.toString())
                    writeAudioFile(libraryRoot, "B")

                    val books by application.inject<BookRepository>()
                    books.upsert(bookFixture("B", "Dragon Secret"))

                    val ownerService = collectionServiceAs(admin.userId, UserRole.ADMIN)
                    val privateCol = ownerService.createPrivateCollection("Private", "B")

                    // Before sharing: m2 cannot reach B (control — the gate is active).
                    client.getBook(m2.token, "B").status shouldBe HttpStatusCode.NotFound
                    client.catchUp(m2.token).items.map { it.id } shouldNotContain "B"

                    // Subscribe m2 to the firehose CONTROL channel, then share. An AccessChanged
                    // control frame must arrive addressed to m2 (the firehose filters control frames
                    // to the addressed user, so receiving one at all proves the per-user emission).
                    client.sse(
                        urlString = "/api/v1/sync/events",
                        request = { bearerAuth(m2.token) },
                    ) {
                        coroutineScope {
                            val controlFrame = async { incoming.first { it.event == "control" } }
                            ownerService.shareCollection(privateCol, m2.userId, SharePermission.Read).requireSuccess()
                            controlFrame.await().data!!.contains("AccessChanged") shouldBe true
                        }
                    }

                    // After sharing: B converges into m2's reachable set via getBook + catch-up.
                    client.getBook(m2.token, "B").status shouldBe HttpStatusCode.OK
                    client.catchUp(m2.token).items.map { it.id } shouldContain "B"

                    // Revoke → B disappears again from getBook + catch-up.
                    ownerService.revokeShare(privateCol, m2.userId).requireSuccess()
                    client.getBook(m2.token, "B").status shouldBe HttpStatusCode.NotFound
                    client.catchUp(m2.token).items.map { it.id } shouldNotContain "B"
                }
            } finally {
                libraryRoot.toFile().deleteRecursively()
            }
        }

        test("a book in ALL_BOOKS is visible to a granted member through the seams") {
            val libraryRoot = Files.createTempDirectory("listenup-seamleak-allbooks-")
            try {
                testApplication {
                    useIsolatedTestConfig(libraryPath = libraryRoot.toString())
                    application { module() }
                    val client = sseJsonClient()

                    val admin = client.runSetup()
                    val m1 = client.registerMember("m1")
                    seedTestLibraryAndFolder(folderPath = libraryRoot.toString())
                    writeAudioFile(libraryRoot, "G")

                    val books by application.inject<BookRepository>()
                    books.upsert(bookFixture("G", "Dragon Global"))

                    // G lives in ALL_BOOKS — the public substrate — which m1 reaches through their
                    // default ALL_BOOKS grant, so under pure union it is visible even though m1
                    // neither owns nor was directly shared the book.
                    makeBookPublic("G")

                    // getBook + catch-up + search: m1 (granted via ALL_BOOKS) sees the book.
                    client.getBook(m1.token, "G").status shouldBe HttpStatusCode.OK
                    client.catchUp(m1.token).items.map { it.id } shouldContain "G"
                    client.search(m1.token, "Dragon").books.map { it.id.value } shouldContain "G"
                }
            } finally {
                libraryRoot.toFile().deleteRecursively()
            }
        }
    })

// ── Multi-user token + id setup (mirrors FirehoseBookAccessTest / BooksDigestRouteAccessTest) ──

/** A registered principal: bearer token + server-issued user id. */
private data class TestUser(
    val token: String,
    val userId: String,
)

private fun ApplicationTestBuilder.sseJsonClient(): HttpClient =
    createClient {
        install(ContentNegotiation) { json(contractJson) }
        install(SSE)
    }

/** Runs first-user setup; returns the ROOT (admin) token + id. */
private suspend fun HttpClient.runSetup(): TestUser {
    val session =
        post("/api/v1/auth/setup") {
            contentType(ContentType.Application.Json)
            setBody(RegisterRequest("root@x", "x".repeat(8), "Root"))
        }.body<AppResult<AuthSession>>()
            .let { it as AppResult.Success<AuthSession> }
            .data
    return TestUser(token = session.accessToken.value, userId = session.user.id.value)
}

/** Registers a MEMBER (OPEN policy); returns token + id. */
private suspend fun HttpClient.registerMember(name: String): TestUser {
    val session =
        post("/api/v1/auth/register") {
            contentType(ContentType.Application.Json)
            setBody(RegisterRequest("$name@x", "y".repeat(8), name))
        }.body<AppResult<RegisterResult>>()
            .let { it as AppResult.Success<RegisterResult> }
            .data
            .let { it as RegisterResult.Authenticated }
            .session
    return TestUser(token = session.accessToken.value, userId = session.user.id.value)
}

// ── Per-seam HTTP calls against the real routes ──

private suspend fun HttpClient.getBook(
    token: String,
    bookId: String,
): HttpResponse = get("/api/v1/books/$bookId") { bearerAuth(token) }

private suspend fun HttpClient.search(
    token: String,
    query: String,
): SearchResults = get("/api/v1/search?query=$query") { bearerAuth(token) }.body()

private suspend fun HttpClient.audio(
    query: String,
    bookId: String,
    fileId: String,
): HttpResponse = get("/api/v1/audio/$bookId/$fileId?$query")

private suspend fun HttpClient.cover(
    token: String,
    bookId: String,
): HttpResponse = get("/api/v1/books/$bookId/cover") { bearerAuth(token) }

private const val CATCH_UP_PATH = "/api/v1/sync/books?since=0&limit=1000"
private const val DIGEST_PATH = "/api/v1/sync/books/digest?cursor=1000000"

private suspend fun HttpClient.catchUp(token: String): Page<BookSyncPayload> = get(CATCH_UP_PATH) { bearerAuth(token) }.body()

private suspend fun HttpClient.digest(token: String): DomainDigest = get(DIGEST_PATH) { bearerAuth(token) }.body()

// ── CollectionService driving (real service, shares the firehose's singleton bus) ──

/**
 * The singleton [CollectionServiceImpl] from the running module, scoped to act as
 * `(userId, role)`. Because it is a Koin singleton sharing the module's [ChangeBus],
 * collection mutations it performs are observable on the live SSE firehose.
 */
private fun io.ktor.server.testing.ApplicationTestBuilder.collectionServiceAs(
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

/**
 * Makes [bookId] publicly visible the pure-union way: drops it into the bootstrap library's
 * `ALL_BOOKS` substrate. The book then reaches every member through their default `ALL_BOOKS`
 * grant (issued at registration) — exactly how a scanned book becomes public under pure union.
 * No explicit grant is issued: members registered via the real auth flow already hold one, and a
 * second live grant for the same `(collection, principal)` would violate the active-grant index.
 */
private suspend fun io.ktor.server.testing.ApplicationTestBuilder.makeBookPublic(bookId: String) {
    val collectionService by application.inject<CollectionServiceImpl>()
    val registry by application.inject<LibraryRegistry>()
    val collectionBookRepo by application.inject<CollectionBookRepository>()
    val libraryId = registry.currentLibrary().value
    val allBooks =
        (
            collectionService.getOrCreateSystemCollection(libraryId, SystemCollectionType.ALL_BOOKS)
                as AppResult.Success
        ).data
    collectionBookRepo
        .upsert(CollectionBookSyncPayload(id = "${allBooks.id.value}:${bookId}", collectionId = allBooks.id.value, bookId = bookId, createdAt = 0L, revision = 0L))
        .requireSuccess()
}

/** Creates a private collection owned by the *acting* caller and adds [bookId]; returns its id. */
private suspend fun CollectionServiceImpl.createPrivateCollection(
    name: String,
    bookId: String,
): CollectionId {
    val created = createCollection("test-library", name)
    require(created is AppResult.Success) { "createCollection failed: $created" }
    addBookToCollection(created.data.id, BookId(bookId)).requireSuccess()
    return created.data.id
}

/** Creates a private collection owned by [ownerId] (acting as that user) and adds [bookId]. */
private suspend fun CollectionServiceImpl.createPrivateCollectionAs(
    ownerId: String,
    name: String,
    bookId: String,
): CollectionId =
    copyWith(PrincipalProvider { UserPrincipal(UserId(ownerId), SessionId("s-$ownerId"), UserRole.MEMBER) })
        .createPrivateCollection(name, bookId)

private suspend fun <T> AppResult<T>.requireSuccess(): T {
    require(this is AppResult.Success) { "expected Success but got $this" }
    return data
}

// ── Book fixture with FTS-matchable title + an on-disk audio file ──

private fun bookFixture(
    id: String,
    title: String,
    withCover: Boolean = false,
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
        cover = if (withCover) CoverPayload(source = CoverSource.FILESYSTEM, hash = "hash-$id") else null,
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
                    size = 256L,
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

/** Writes a small fixture audio file at `books/<bookId>/01.m4b` under [libraryRoot]. */
private fun writeAudioFile(
    libraryRoot: java.nio.file.Path,
    bookId: String,
) {
    val dir = Files.createDirectories(libraryRoot.resolve("books/$bookId"))
    Files.write(dir.resolve("01.m4b"), ByteArray(256) { it.toByte() })
}

/** Writes a fixture `cover.jpg` at `books/<bookId>/` so the filesystem cover route serves bytes. */
private fun writeCoverFile(
    libraryRoot: java.nio.file.Path,
    bookId: String,
) {
    val dir = Files.createDirectories(libraryRoot.resolve("books/$bookId"))
    Files.write(
        dir.resolve("cover.jpg"),
        byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 0x00, 0x10, 'J'.code.toByte()),
    )
}
