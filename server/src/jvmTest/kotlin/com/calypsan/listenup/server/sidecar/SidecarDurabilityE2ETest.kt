package com.calypsan.listenup.server.sidecar

import com.calypsan.listenup.api.contractJson
import com.calypsan.listenup.api.dto.BookUpdate
import com.calypsan.listenup.api.dto.ChapterInput
import com.calypsan.listenup.api.dto.auth.AuthSession
import com.calypsan.listenup.api.dto.auth.RegisterRequest
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.sync.BookSyncPayload
import com.calypsan.listenup.api.sync.ChapterSource
import com.calypsan.listenup.api.sync.Page
import com.calypsan.listenup.api.sync.UserEditedField
import com.calypsan.listenup.server.db.sqldelight.ListenUpDatabase
import com.calypsan.listenup.server.embeddedmeta.fixtures.buildMp3File
import com.calypsan.listenup.server.io.hashBytesSha256
import com.calypsan.listenup.server.module
import com.calypsan.listenup.server.testing.useIsolatedTestConfig
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.testApplication
import java.nio.file.Files
import kotlin.io.path.div
import kotlin.io.path.exists
import kotlin.io.path.readBytes
import kotlin.io.path.writeBytes
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import org.koin.ktor.ext.inject

private const val AWAIT_TIMEOUT_MS = 30_000L
private const val POLL_INTERVAL_MS = 200L

/**
 * The reason Phase 2 exists: **curation survives a database wipe.**
 *
 * Real everything — real server (`testApplication` + `module()`), real temp library on
 * disk, real scans, real broker writes, real debounce. One journey:
 *
 *  1. Server A scans a book; the user edits its title and saves USER chapters.
 *  2. The debounced writer lands `listenup.json` beside the audio; the write-state row
 *     records its content hash (the round-trip discriminator).
 *  3. The database is lost (server B boots with a FRESH db against the SAME folder).
 *  4. Server B's scan re-ingests the sidecar: title restored, TITLE protection restored,
 *     USER chapters restored — and ingestion triggers NO writer echo (write-state stays
 *     empty on B, the file bytes stay untouched).
 *  5. The "friend's file" case: `listenup.json` is overwritten externally with a new
 *     description; a rescan ingests it.
 */
class SidecarDurabilityE2ETest :
    FunSpec({

        test("curation survives a DB wipe via listenup.json, and external edits ingest on rescan") {
            val libraryDir = Files.createTempDirectory("sidecar-durability-e2e-")
            val bookDir = (libraryDir / "Brandon Sanderson" / "The Way of Kings").apply { Files.createDirectories(this) }
            (bookDir / "01.mp3").writeBytes(
                buildMp3File {
                    id3v2(version = 4) {
                        textFrame("TIT2", "The Way of Kings")
                        textFrame("TPE1", "Brandon Sanderson")
                    }
                    mpegFrames(durationSeconds = 10)
                },
            )
            val sidecarFile = bookDir / "listenup.json"

            try {
                // ── Server A: scan, curate, and let the writer land the sidecar ──
                var selfWrittenBytes: ByteArray? = null
                testApplication {
                    useIsolatedTestConfig(libraryPath = libraryDir.toString())
                    application { module() }
                    val client = createClient { install(ContentNegotiation) { json(contractJson) } }
                    val token = client.mintRootToken()

                    val book = client.awaitBook(token)
                    client
                        .patch("/api/v1/books/${book.id}") {
                            bearerAuth(token)
                            contentType(ContentType.Application.Json)
                            setBody(BookUpdate(title = "The Way of Kings (Annotated)"))
                        }.status shouldBe HttpStatusCode.NoContent
                    client
                        .put("/api/v1/books/${book.id}/chapters") {
                            bearerAuth(token)
                            contentType(ContentType.Application.Json)
                            setBody(
                                listOf(
                                    ChapterInput(id = "c1", title = "Prelude", startTime = 0L, duration = 4_000L),
                                    ChapterInput(id = "c2", title = "Chapter One", startTime = 4_000L, duration = 6_000L),
                                ),
                            )
                        }.status shouldBe HttpStatusCode.NoContent

                    // Await the debounced write: the file must exist AND carry both edits.
                    withTimeout(AWAIT_TIMEOUT_MS) {
                        while (true) {
                            if (sidecarFile.exists()) {
                                val parsed = SidecarJson.parseOrNull(sidecarFile.readBytes())
                                if (parsed?.metadata?.title == "The Way of Kings (Annotated)" &&
                                    parsed.chapters?.entries?.size == 2
                                ) {
                                    break
                                }
                            }
                            delay(POLL_INTERVAL_MS)
                        }
                    }

                    val bytes = sidecarFile.readBytes()
                    selfWrittenBytes = bytes
                    val parsed = SidecarJson.parseOrNull(bytes)
                    parsed.shouldNotBeNull()
                    parsed.userEditedFields shouldContain "TITLE"
                    parsed.chapters?.source shouldBe "USER"

                    // The write-state row records exactly the landed file's content hash.
                    val db by application.inject<ListenUpDatabase>()
                    val state = SidecarWriteStateRepository(db).findByBookId(book.id)
                    state.shouldNotBeNull()
                    state.contentHashHex shouldBe hashBytesSha256(bytes)
                }

                // ── Server B: FRESH database, same library folder — the DB-wipe case ──
                testApplication {
                    useIsolatedTestConfig(libraryPath = libraryDir.toString())
                    application { module() }
                    val client = createClient { install(ContentNegotiation) { json(contractJson) } }
                    val token = client.mintRootToken()

                    // Rescan on the fresh DB re-ingests the sidecar: curation restored.
                    val restored =
                        awaitBookMatching(client, token) { it.title == "The Way of Kings (Annotated)" }
                    restored.userEditedFields shouldContain UserEditedField.TITLE
                    restored.chapterSource shouldBe ChapterSource.USER
                    restored.chapters.map { it.title } shouldBe listOf("Prelude", "Chapter One")

                    // No write echo: ingestion must not re-trigger the SidecarWriter. The file
                    // bytes are still exactly server A's write, and B has no write-state row.
                    sidecarFile.readBytes() shouldBe selfWrittenBytes.shouldNotBeNull()
                    val db by application.inject<ListenUpDatabase>()
                    SidecarWriteStateRepository(db).findByBookId(restored.id) shouldBe null

                    // ── The "friend's file" case: an external edit ingests on rescan ──
                    val friendSidecar = SidecarJson.parseOrNull(sidecarFile.readBytes()).shouldNotBeNull()
                    sidecarFile.writeBytes(
                        SidecarJson.serialize(
                            friendSidecar.copy(
                                metadata = friendSidecar.metadata.copy(description = "A friend's description."),
                                userEditedFields = friendSidecar.userEditedFields + "DESCRIPTION",
                            ),
                        ),
                    )
                    client.post("/api/v1/libraries/scan") { bearerAuth(token) }.status shouldBe HttpStatusCode.Accepted
                    val updated =
                        awaitBookMatching(client, token) { it.description == "A friend's description." }
                    updated.userEditedFields shouldContain UserEditedField.DESCRIPTION
                }
            } finally {
                libraryDir.toFile().deleteRecursively()
            }
        }
    })

private suspend fun HttpClient.mintRootToken(): String =
    post("/api/v1/auth/setup") {
        contentType(ContentType.Application.Json)
        setBody(RegisterRequest("root@x", "x".repeat(8), "Root"))
    }.body<AppResult<AuthSession>>()
        .let { it as AppResult.Success<AuthSession> }
        .data.accessToken.value

/** Polls the sync-books page until exactly one book appears; returns it. */
private suspend fun HttpClient.awaitBook(token: String): BookSyncPayload =
    awaitBookMatching(this, token) { true }

/** Polls the sync-books page until a book matching [predicate] appears; returns it. */
private suspend fun awaitBookMatching(
    client: HttpClient,
    token: String,
    predicate: (BookSyncPayload) -> Boolean,
): BookSyncPayload =
    withTimeout(AWAIT_TIMEOUT_MS) {
        while (true) {
            val text =
                client
                    .get("/api/v1/sync/books?since=0") { bearerAuth(token) }
                    .bodyAsText()
            val page = contractJson.decodeFromString(Page.serializer(BookSyncPayload.serializer()), text)
            page.items.firstOrNull(predicate)?.let { return@withTimeout it }
            delay(POLL_INTERVAL_MS)
        }
        @Suppress("UNREACHABLE_CODE")
        error("unreachable")
    }
