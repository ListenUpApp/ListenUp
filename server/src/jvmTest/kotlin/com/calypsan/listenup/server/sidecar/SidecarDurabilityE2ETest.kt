package com.calypsan.listenup.server.sidecar

import com.calypsan.listenup.api.BookService
import com.calypsan.listenup.api.ScannerService
import com.calypsan.listenup.api.dto.BookUpdate
import com.calypsan.listenup.api.dto.ChapterInput
import com.calypsan.listenup.api.metadata.BookField
import com.calypsan.listenup.api.metadata.FieldProvenance
import com.calypsan.listenup.api.metadata.FieldSourceKind
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.sync.BookSyncPayload
import com.calypsan.listenup.api.sync.ChapterSource
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.server.db.sqldelight.ListenUpDatabase
import com.calypsan.listenup.server.embeddedmeta.fixtures.buildMp3File
import com.calypsan.listenup.server.io.hashBytesSha256
import com.calypsan.listenup.server.module
import com.calypsan.listenup.server.services.BookRepository
import com.calypsan.listenup.server.services.LibraryRegistry
import com.calypsan.listenup.server.testing.authedService
import com.calypsan.listenup.server.testing.setupRootUser
import com.calypsan.listenup.server.testing.useIsolatedTestConfig
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.ktor.server.testing.ApplicationTestBuilder
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
private const val CURATED_TITLE = "The Way of Kings (Annotated)"

/**
 * The reason Phase 2 exists: **curation survives a database wipe.**
 *
 * Real everything — real server (`testApplication` + `module()`), real temp library on
 * disk, real startup scans, real RPC edits, real broker writes, real debounce. One journey:
 *
 *  1. Server A scans a book; the user edits its title and saves USER chapters over RPC.
 *  2. The debounced writer lands `listenup.json` beside the audio; the write-state row
 *     records its content hash (the round-trip discriminator).
 *  3. The database is lost (server B boots with a FRESH db against the SAME folder).
 *  4. Server B's scan re-ingests the sidecar: the title is restored **at the USER tier it
 *     was recorded at**, so a later scan cannot overwrite it; USER chapters come back too —
 *     and ingestion triggers NO writer echo (write-state stays empty on B, the file bytes
 *     stay untouched).
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
            // Not a fixed path: the seeded folder IS this book's canonical path, so retitling it
            // relocates the whole folder (OrganizeOnEditRelocator — conformance is maintained), and
            // the sidecar lands beside the audio at the book's NEW home. The test follows the book.
            var sidecarFile = bookDir / "listenup.json"

            try {
                // ── Server A: scan, curate over RPC, and let the writer land the sidecar ──
                var selfWrittenBytes: ByteArray? = null
                testApplication {
                    useIsolatedTestConfig(libraryPath = libraryDir.toString())
                    application { module() }
                    val root = setupRootUser()
                    val books = authedService<BookService>(root.token)
                    val bookId = awaitScannedBookId()

                    books
                        .updateBook(BookId(bookId), BookUpdate(title = CURATED_TITLE))
                        .shouldBeInstanceOf<AppResult.Success<Unit>>()
                    books
                        .setBookChapters(
                            BookId(bookId),
                            listOf(
                                ChapterInput(id = "c1", title = "Prelude", startTime = 0L, duration = 4_000L),
                                ChapterInput(id = "c2", title = "Chapter One", startTime = 4_000L, duration = 6_000L),
                            ),
                        ).shouldBeInstanceOf<AppResult.Success<Unit>>()

                    // Await BOTH debounced background jobs — the sidecar write and the organizer's
                    // edit-relocation — by resolving the sidecar from the book's live path on every
                    // poll, and only settling once the file there carries both edits.
                    val db by application.inject<ListenUpDatabase>()
                    withTimeout(AWAIT_TIMEOUT_MS) {
                        while (true) {
                            val candidate = libraryDir / currentRootRelPath(db, bookId) / "listenup.json"
                            if (candidate.exists()) {
                                val parsed = SidecarJson.parseOrNull(candidate.readBytes())
                                if (parsed?.metadata?.title == CURATED_TITLE && parsed.chapters?.entries?.size == 2) {
                                    sidecarFile = candidate
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
                    // The provenance is recorded per field, at the tier the edit carried.
                    parsed.fieldProvenance["TITLE"]?.kind shouldBe FieldSourceKind.USER
                    parsed.chapters?.source shouldBe "USER"

                    // The write-state row records exactly the landed file's content hash.
                    val state = SidecarWriteStateRepository(db).findByBookId(bookId)
                    state.shouldNotBeNull()
                    state.contentHashHex shouldBe hashBytesSha256(bytes)
                }

                // ── Server B: FRESH database, same library folder — the DB-wipe case ──
                testApplication {
                    useIsolatedTestConfig(libraryPath = libraryDir.toString())
                    application { module() }
                    val root = setupRootUser()

                    // The startup rescan on the fresh DB re-ingests the sidecar: curation restored.
                    val restoredId = awaitScannedBookId()
                    val repo by application.inject<BookRepository>()
                    val restored = awaitBookMatching(repo, restoredId) { it.title == CURATED_TITLE }
                    // Restored AT ITS RECORDED TIER — a tier-0 restore would leave the title open to
                    // the next scan re-deriving it from the embedded tag.
                    restored.fieldProvenance[BookField.TITLE]?.kind shouldBe FieldSourceKind.USER
                    restored.chapterSource shouldBe ChapterSource.USER
                    restored.chapters.map { it.title } shouldBe listOf("Prelude", "Chapter One")

                    // A second scan does not undo the restore: the USER tier out-ranks the files.
                    authedService<ScannerService>(root.token).scanFull()
                    repo.findById(BookId(restoredId))?.title shouldBe CURATED_TITLE

                    // No write echo: ingestion must not re-trigger the SidecarWriter. The file
                    // bytes are still exactly server A's write, and B has no write-state row.
                    sidecarFile.readBytes() shouldBe selfWrittenBytes.shouldNotBeNull()
                    val db by application.inject<ListenUpDatabase>()
                    SidecarWriteStateRepository(db).findByBookId(restoredId) shouldBe null

                    // ── The "friend's file" case: an external edit ingests on rescan ──
                    val friendSidecar = SidecarJson.parseOrNull(sidecarFile.readBytes()).shouldNotBeNull()
                    sidecarFile.writeBytes(
                        SidecarJson.serialize(
                            friendSidecar.copy(
                                metadata = friendSidecar.metadata.copy(description = "A friend's description."),
                                fieldProvenance =
                                    friendSidecar.fieldProvenance +
                                        ("DESCRIPTION" to FieldProvenance(FieldSourceKind.USER, at = 42L)),
                            ),
                        ),
                    )
                    authedService<ScannerService>(root.token).scanFull()
                    val updated =
                        awaitBookMatching(repo, restoredId) { it.description == "A friend's description." }
                    updated.fieldProvenance[BookField.DESCRIPTION] shouldBe
                        FieldProvenance(FieldSourceKind.USER, at = 42L)
                }
            } finally {
                libraryDir.toFile().deleteRecursively()
            }
        }
    })

/** Polls the Koin-wired database until the startup scan has landed a book; returns its id. */
private suspend fun ApplicationTestBuilder.awaitScannedBookId(): String {
    val db by application.inject<ListenUpDatabase>()
    val registry by application.inject<LibraryRegistry>()
    return withTimeout(AWAIT_TIMEOUT_MS) {
        while (true) {
            val libraryId = registry.currentLibrary()
            db.booksQueries
                .selectLiveIdsAndPathsForLibrary(libraryId.value)
                .executeAsList()
                .firstOrNull()
                ?.let { return@withTimeout it.id }
            delay(POLL_INTERVAL_MS)
        }
        @Suppress("UNREACHABLE_CODE")
        error("unreachable")
    }
}

/** [bookId]'s stored `root_rel_path` — where the book lives *now*, after any organizer relocation. */
private fun currentRootRelPath(
    db: ListenUpDatabase,
    bookId: String,
): String =
    db.booksQueries
        .selectById(bookId)
        .executeAsOne()
        .root_rel_path

/** Polls the repository until the book at [bookId] matches [predicate]; returns it. */
private suspend fun awaitBookMatching(
    repo: BookRepository,
    bookId: String,
    predicate: (BookSyncPayload) -> Boolean,
): BookSyncPayload =
    withTimeout(AWAIT_TIMEOUT_MS) {
        while (true) {
            repo.findById(BookId(bookId))?.takeIf(predicate)?.let { return@withTimeout it }
            delay(POLL_INTERVAL_MS)
        }
        @Suppress("UNREACHABLE_CODE")
        error("unreachable")
    }
