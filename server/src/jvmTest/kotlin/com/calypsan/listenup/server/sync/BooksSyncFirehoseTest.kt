package com.calypsan.listenup.server.sync

import com.calypsan.listenup.api.sync.BookAudioFilePayload
import com.calypsan.listenup.api.sync.BookChapterPayload
import com.calypsan.listenup.api.sync.BookSyncPayload
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.FolderId
import com.calypsan.listenup.core.LibraryId
import com.calypsan.listenup.server.module
import com.calypsan.listenup.server.services.BookRepository
import com.calypsan.listenup.server.testing.domainFrames
import com.calypsan.listenup.server.testing.rootPrincipal
import com.calypsan.listenup.server.testing.rpcFirehose
import com.calypsan.listenup.server.testing.seedTestLibraryAndFolder
import com.calypsan.listenup.server.testing.useIsolatedTestConfig
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import org.koin.ktor.ext.inject
import java.nio.file.Files

/**
 * Tier-2 integration tests: Books firehose ordering via the RPC stream
 * ([SyncStreamServiceImpl] over the app's real [ChangeBus]).
 *
 * Verifies that the Books domain emits the correct sequence of
 * `SyncEvent.Created`, `SyncEvent.Updated`, and `SyncEvent.Deleted`
 * events on the firehose, in revision order, on the `books` wire domain.
 *
 * Boots the full [module] (same approach as
 * [com.calypsan.listenup.server.routes.BookRoutesTest]) because
 * [BookRepository] requires `booksModule` + `scannerModule`. The bus's
 * replay buffer makes collection deterministic: writes land first, the
 * subscriber then replays them in publish order — no busy-wait.
 */
class BooksSyncFirehoseTest :
    FunSpec({

        test("firehose emits Created then Updated then Deleted for the books domain in revision order") {
            val libraryRoot = Files.createTempDirectory("listenup-books-firehose-test-")
            try {
                testApplication {
                    useIsolatedTestConfig(libraryPath = libraryRoot.toString())
                    application { module() }
                    startApplication()
                    seedTestLibraryAndFolder()
                    val repo by application.inject<BookRepository>()
                    val bus by application.inject<ChangeBus>()

                    // Create
                    repo.upsert(bookSyncFixture(id = "fh-book", title = "Original Title"))
                    // Update (same id → Updated)
                    repo.upsert(bookSyncFixture(id = "fh-book", title = "Updated Title"))
                    // Delete
                    repo.softDelete(BookId("fh-book"))

                    val events =
                        rpcFirehose(bus, rootPrincipal())
                            .domainFrames()
                            .filter { it.domain == "books" }
                            .take(3)
                            .toList()

                    // All three events must be on the "books" domain
                    events.forEach { frame -> frame.domain shouldBe "books" }

                    // Revision ids must increase monotonically
                    val revisions = events.map { it.revision!! }
                    revisions shouldBe revisions.sorted()

                    // Event type ordering: Created → Updated → Deleted
                    events[0].json shouldContain """"type":"SyncEvent.Created""""
                    events[1].json shouldContain """"type":"SyncEvent.Updated""""
                    events[2].json shouldContain """"type":"SyncEvent.Deleted""""

                    // Created and Updated carry the book id
                    events[0].json shouldContain """"id":"fh-book""""
                    events[1].json shouldContain """"id":"fh-book""""
                    events[2].json shouldContain """"id":"fh-book""""
                }
            } finally {
                libraryRoot.toFile().deleteRecursively()
            }
        }

        test("firehose emits only books domain events with correct domain field") {
            val libraryRoot = Files.createTempDirectory("listenup-books-firehose-domain-")
            try {
                testApplication {
                    useIsolatedTestConfig(libraryPath = libraryRoot.toString())
                    application { module() }
                    startApplication()
                    seedTestLibraryAndFolder()
                    val repo by application.inject<BookRepository>()
                    val bus by application.inject<ChangeBus>()

                    repo.upsert(bookSyncFixture(id = "domain-book", title = "Domain Test"))

                    val frame =
                        rpcFirehose(bus, rootPrincipal())
                            .domainFrames()
                            .first { it.domain == "books" }

                    frame.domain shouldBe "books"
                    frame.json shouldContain """"type":"SyncEvent.Created""""
                    frame.json shouldContain """"id":"domain-book""""
                }
            } finally {
                libraryRoot.toFile().deleteRecursively()
            }
        }
    })

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
        // Contributors/series left empty: this test asserts only on firehose event
        // shape, not the aggregate's child rows. Junction-row writes require
        // pre-resolved catalogue ids (see BookRepository.replaceContributors).
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
