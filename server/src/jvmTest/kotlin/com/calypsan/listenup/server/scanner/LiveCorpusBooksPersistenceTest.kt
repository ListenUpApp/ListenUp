package com.calypsan.listenup.server.scanner

import com.calypsan.listenup.api.contractJson
import com.calypsan.listenup.api.dto.auth.AuthSession
import com.calypsan.listenup.api.dto.auth.LoginRequest
import com.calypsan.listenup.api.dto.auth.RegisterRequest
import com.calypsan.listenup.api.dto.scanner.ScanResult
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.sync.BookSyncPayload
import com.calypsan.listenup.api.sync.Page
import com.calypsan.listenup.server.module
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.cio.CIO as ServerCIO
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.engine.EngineConnectorBuilder
import io.ktor.server.engine.applicationEnvironment
import io.ktor.server.engine.embeddedServer
import java.nio.file.Files
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.serializer

/**
 * Env-gated live-corpus regression guard for Books persistence.
 *
 * Boots the full server against a real audiobook library on disk, waits for
 * the bootstrap scan to complete, then verifies that every scanned book was
 * persisted with its aggregate intact.
 *
 * **Skipped in CI** — the env var [LIVE_CORPUS_ENV] is unset on shared
 * infrastructure. Run locally with:
 *
 * ```
 * export LISTENUP_EMBEDDEDMETA_LIVE_DIR=/path/to/your/audiobook/library
 * ./gradlew :server:test --tests "*.LiveCorpusBooksPersistenceTest"
 * ```
 *
 * [LIVE_CORPUS_ENV] must be an absolute path to a directory that contains
 * audiobook sub-directories in the format the scanner expects
 * (e.g. `Author/Title/track.mp3`). The same env var used by
 * [LiveCorpusValidationTest] works here — both tests target the same corpus.
 *
 * The core regression guard: the scanner's `ScanResult.books` count must
 * equal the number of non-deleted books that land in the DB. If
 * [com.calypsan.listenup.server.services.BookPersister] silently drops books
 * (serialization bug, transaction failure, identity-resolution crash), this
 * test catches it.
 */
class LiveCorpusBooksPersistenceTest :
    FunSpec({
        val liveDir = System.getenv(LIVE_CORPUS_ENV)

        test("every book scanned from the live corpus is persisted with a non-blank title")
            .config(enabled = liveDir != null) {
                val corpusPath = liveDir!!

                // Boot a real embedded server pointing at the live corpus.
                // Mirrors ScannerEndToEndFixture but accepts an existing library
                // path rather than creating a synthetic temp directory — the
                // corpus is read-only and must not be deleted on close.
                val tmpDb =
                    Files
                        .createTempFile("listenup-live-corpus-", ".db")
                        .toFile()
                        .apply { deleteOnExit() }

                val env =
                    applicationEnvironment {
                        config =
                            MapApplicationConfig(
                                "database.jdbcUrl" to "jdbc:sqlite:${tmpDb.absolutePath}",
                                "auth.refreshPepper" to "x".repeat(PEPPER_LENGTH),
                                "jwt.secret" to "x".repeat(JWT_SECRET_LENGTH),
                                "jwt.issuer" to "listenup",
                                "jwt.audience" to "listenup-client",
                                "registration.policy" to "OPEN",
                                "mdns.enabled" to "false",
                                "scanner.libraryPath" to corpusPath,
                            )
                    }

                val server =
                    embeddedServer(
                        factory = ServerCIO,
                        environment = env,
                        configure = {
                            connectors.add(
                                EngineConnectorBuilder().apply {
                                    host = "127.0.0.1"
                                    port = 0
                                },
                            )
                        },
                    ) {
                        module()
                    }

                server.start(wait = false)
                try {
                    val resolvedPort =
                        runBlocking { server.engine.resolvedConnectors() }.first().port
                    val baseUrl = "http://127.0.0.1:$resolvedPort"

                    val client =
                        HttpClient(CIO) {
                            install(ContentNegotiation) { json(contractJson) }
                            install(HttpTimeout) { requestTimeoutMillis = HTTP_TIMEOUT_MS }
                        }

                    try {
                        // Mint an auth token first — required for both the now JWT-gated
                        // /api/v1/scan/last route and the sync (catch-up) route below.
                        val token = mintAccessToken(client, baseUrl)

                        // Wait for the bootstrap scan to complete. Real corpora can be large;
                        // use a generous timeout to avoid false failures on slow disks.
                        val scanResult = waitForScanResult(client, baseUrl, token, timeoutMs = SCAN_TIMEOUT_MS)

                        val scannedCount = scanResult.books.size
                        withClue("Live corpus scan produced zero books — check $LIVE_CORPUS_ENV points to a valid audiobook library") {
                            scannedCount shouldBeGreaterThan 0
                        }

                        // BookPersister drains the scan-result bus asynchronously in its own
                        // coroutine. waitForScanResult() above guarantees the scan itself is
                        // done (scannedCount is final), but persistence may still be in flight.
                        // Poll until persistedCount converges to scannedCount or the deadline
                        // passes, then run assertions against the converged snapshot.
                        val persistenceDeadline = System.currentTimeMillis() + PERSISTENCE_CONVERGENCE_TIMEOUT_MS
                        var persistedBooks: List<BookSyncPayload>
                        var persistedCount: Int
                        do {
                            persistedBooks = pullAllBooks(client, baseUrl, token, limit = scannedCount + LIMIT_HEADROOM)
                            persistedCount = persistedBooks.count { it.deletedAt == null }
                            if (persistedCount < scannedCount) delay(POLL_INTERVAL_MS)
                        } while (persistedCount < scannedCount && System.currentTimeMillis() < persistenceDeadline)

                        withClue(
                            "Scanner found $scannedCount books but only $persistedCount landed in the DB " +
                                "without a tombstone after ${PERSISTENCE_CONVERGENCE_TIMEOUT_MS}ms. " +
                                "BookPersister dropped ${scannedCount - persistedCount} books.",
                        ) {
                            persistedCount shouldBe scannedCount
                        }

                        // Structural sanity: every persisted book has a non-blank title.
                        // A blank title means the scanner's title-resolution or the
                        // BookSyncPayload mapping is broken for that book.
                        val blankTitles = persistedBooks.filter { it.deletedAt == null && it.title.isBlank() }
                        withClue(
                            "${blankTitles.size} persisted book(s) have a blank title. " +
                                "First offender rootRelPath: ${blankTitles.firstOrNull()?.rootRelPath}",
                        ) {
                            blankTitles.size shouldBe 0
                        }

                        // Structural sanity: every persisted book has at least one audio file.
                        // The scanner only groups directories that contain audio files;
                        // a persisted book with zero audio files indicates a mapping gap.
                        val noAudio = persistedBooks.filter { it.deletedAt == null && it.audioFiles.isEmpty() }
                        withClue(
                            "${noAudio.size} persisted book(s) have zero audio files. " +
                                "First offender: ${noAudio.firstOrNull()?.title}",
                        ) {
                            noAudio.size shouldBe 0
                        }
                    } finally {
                        client.close()
                    }
                } finally {
                    @Suppress("MagicNumber")
                    server.stop(gracePeriodMillis = 100, timeoutMillis = 500)
                    tmpDb.delete()
                }
            }
    })

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

/**
 * Polls `GET /api/v1/scan/last` until it returns Success, indicating the
 * bootstrap scan has completed and [com.calypsan.listenup.server.services.BookPersister]
 * has drained the result. The persister subscribes to the scan-result bus
 * before the scan launches (see [com.calypsan.listenup.server.Application]),
 * so a Success result from this endpoint means persistence is complete.
 */
private suspend fun waitForScanResult(
    client: HttpClient,
    baseUrl: String,
    token: String,
    timeoutMs: Long,
): ScanResult {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < deadline) {
        val response = client.get("$baseUrl/api/v1/scan/last") { bearerAuth(token) }
        if (response.status == HttpStatusCode.OK) {
            val text: String = response.body()
            val result =
                contractJson.decodeFromString(
                    AppResult.serializer(serializer<ScanResult>()),
                    text,
                )
            if (result is AppResult.Success) return result.data
        }
        delay(POLL_INTERVAL_MS)
    }
    error("Bootstrap scan did not complete within ${timeoutMs}ms — is the corpus readable?")
}

/**
 * Seeds the root account and returns an access token. The live corpus test
 * always boots against a fresh DB, so there is no pre-existing account.
 */
private suspend fun mintAccessToken(
    client: HttpClient,
    baseUrl: String,
): String {
    client.post("$baseUrl/api/v1/auth/setup") {
        contentType(ContentType.Application.Json)
        setBody(RegisterRequest("root@listenup.test", "x".repeat(MIN_PASSWORD_LENGTH), "Root"))
    }
    val response =
        client.post("$baseUrl/api/v1/auth/login") {
            contentType(ContentType.Application.Json)
            setBody(LoginRequest("root@listenup.test", "x".repeat(MIN_PASSWORD_LENGTH)))
        }
    return response
        .body<AppResult<AuthSession>>()
        .shouldBeInstanceOf<AppResult.Success<AuthSession>>()
        .data
        .accessToken
        .value
}

/**
 * Fetches all books from the sync catch-up route, paging until [Page.hasMore]
 * is false. Returns the full flat list including soft-deleted rows so the
 * caller can filter and count independently.
 */
private suspend fun pullAllBooks(
    client: HttpClient,
    baseUrl: String,
    token: String,
    limit: Int,
): List<BookSyncPayload> {
    val all = mutableListOf<BookSyncPayload>()
    var cursor = 0L
    do {
        val response =
            client.get("$baseUrl/api/v1/sync/books") {
                bearerAuth(token)
                url {
                    parameters.append("since", cursor.toString())
                    parameters.append("limit", limit.toString())
                }
            }
        val text: String = response.body()
        val page =
            contractJson.decodeFromString(
                Page.serializer(BookSyncPayload.serializer()),
                text,
            )
        all.addAll(page.items)
        cursor = page.nextCursor ?: break
    } while (page.hasMore)
    return all
}

private const val LIVE_CORPUS_ENV = "LISTENUP_EMBEDDEDMETA_LIVE_DIR"

// Server auth constants — lengths match the production validation rules.
private const val PEPPER_LENGTH = 32
private const val JWT_SECRET_LENGTH = 32
private const val MIN_PASSWORD_LENGTH = 8

// Timeouts for a real, potentially large corpus on disk.
private const val SCAN_TIMEOUT_MS = 5L * 60L * 1_000L // 5 minutes
private const val HTTP_TIMEOUT_MS = 30_000L

// Polling cadence while waiting for the bootstrap scan or persistence convergence.
private const val POLL_INTERVAL_MS = 500L

// How long to wait for BookPersister to drain the scan-result bus after the
// scan itself completes. Persistence is async; on a large corpus the persister
// may still be writing rows when waitForScanResult() returns.
private const val PERSISTENCE_CONVERGENCE_TIMEOUT_MS = 60_000L

// A small headroom above scannedCount so the limit never inadvertently
// caps the sync query at exactly the scan count (edge case: if one extra
// book exists from a previous run, the query would still return it).
private const val LIMIT_HEADROOM = 100
