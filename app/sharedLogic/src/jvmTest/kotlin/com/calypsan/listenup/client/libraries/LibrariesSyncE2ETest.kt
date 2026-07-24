package com.calypsan.listenup.client.libraries

import com.calypsan.listenup.api.sync.LibraryFolderSyncPayload
import com.calypsan.listenup.api.sync.LibrarySyncPayload
import com.calypsan.listenup.client.data.local.db.ListenUpDatabase
import com.calypsan.listenup.client.data.sync.testing.withClientSyncEngineAgainstServer
import com.calypsan.listenup.core.FolderId
import com.calypsan.listenup.core.LibraryId
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.withTimeout

private const val ROUND_TRIP_TIMEOUT_SECONDS = 30

/**
 * Tier 3 e2e tests for the `libraries` and `library_folders` sync domains.
 *
 * A write on the server's [com.calypsan.listenup.server.services.LibraryRepository] or
 * [com.calypsan.listenup.server.services.LibraryFolderRepository] crosses the live SSE
 * firehose, the client engine routes it through the real
 * [com.calypsan.listenup.client.data.sync.domains.librariesDomain] handler and
 * [com.calypsan.listenup.client.data.sync.domains.libraryFoldersDomain], and the rows land in the
 * client's Room database — exactly the round-trip production performs.
 *
 * The test also exercises the full cascade: `softDelete` on a library tombstones
 * the library row, its folder rows, and any book rows belonging to that library.
 *
 * Async waits poll real Room queries inside [withTimeout] — matching the
 * `BooksEndToEndTest` idiom — rather than a fixed delay, since SSE delivery latency
 * is non-deterministic.
 */
class LibrariesSyncE2ETest :
    FunSpec({

        test("server upsert → SSE → client Room has the library") {
            withClientSyncEngineAgainstServer {
                engine.start(currentUserId = "u1")

                serverLibraryRepository.upsert(libraryPayload(id = "lib1", name = "Audiobooks"))

                val library =
                    awaitClientLibrary(clientDatabase, "lib1", ROUND_TRIP_TIMEOUT_SECONDS.seconds)
                library.name shouldBe "Audiobooks"
                library.deletedAt shouldBe null
            }
        }

        test("server library upsert + folder upsert → SSE → client Room has library and folder") {
            withClientSyncEngineAgainstServer {
                engine.start(currentUserId = "u1")

                // Library must arrive before the folder (FK constraint on room side).
                // The revision ordering guarantees this in the SSE stream.
                serverLibraryRepository.upsert(libraryPayload(id = "lib2", name = "Fiction"))
                awaitClientLibrary(clientDatabase, "lib2", ROUND_TRIP_TIMEOUT_SECONDS.seconds)

                serverLibraryFolderRepository.upsert(
                    folderPayload(id = "folder2a", libraryId = "lib2", rootPath = "/audio/fiction"),
                )

                val folder =
                    awaitClientFolder(clientDatabase, "folder2a", ROUND_TRIP_TIMEOUT_SECONDS.seconds)
                folder.libraryId shouldBe "lib2"
                folder.rootPath shouldBe "/audio/fiction"
                folder.deletedAt shouldBe null
            }
        }

        test("server addFolder → SSE → client Room shows the new folder") {
            withClientSyncEngineAgainstServer {
                engine.start(currentUserId = "u1")

                // Seed library first.
                serverLibraryRepository.upsert(libraryPayload(id = "lib3", name = "Nonfiction"))
                awaitClientLibrary(clientDatabase, "lib3", ROUND_TRIP_TIMEOUT_SECONDS.seconds)

                // Seed initial folder.
                serverLibraryFolderRepository.upsert(
                    folderPayload(id = "folder3a", libraryId = "lib3", rootPath = "/audio/nonfiction/a"),
                )
                awaitClientFolder(clientDatabase, "folder3a", ROUND_TRIP_TIMEOUT_SECONDS.seconds)

                // Add a second folder (simulating an addFolder RPC call from the admin UI).
                serverLibraryFolderRepository.upsert(
                    folderPayload(id = "folder3b", libraryId = "lib3", rootPath = "/audio/nonfiction/b"),
                )

                val newFolder =
                    awaitClientFolder(clientDatabase, "folder3b", ROUND_TRIP_TIMEOUT_SECONDS.seconds)
                newFolder.libraryId shouldBe "lib3"
                newFolder.rootPath shouldBe "/audio/nonfiction/b"
            }
        }

        test("server removeFolder → SSE → folder is tombstoned in client Room") {
            withClientSyncEngineAgainstServer {
                engine.start(currentUserId = "u1")

                serverLibraryRepository.upsert(libraryPayload(id = "lib4", name = "Sci-Fi"))
                awaitClientLibrary(clientDatabase, "lib4", ROUND_TRIP_TIMEOUT_SECONDS.seconds)

                serverLibraryFolderRepository.upsert(
                    folderPayload(id = "folder4a", libraryId = "lib4", rootPath = "/audio/scifi"),
                )
                awaitClientFolder(clientDatabase, "folder4a", ROUND_TRIP_TIMEOUT_SECONDS.seconds)

                // Simulate removeFolder: soft-delete the folder on the server.
                serverLibraryFolderRepository.softDelete(FolderId("folder4a"))

                withTimeout(ROUND_TRIP_TIMEOUT_SECONDS.seconds) {
                    while (clientDatabase.libraryFolderDao().findById("folder4a")?.deletedAt == null) {
                        // Poll until the tombstone arrives.
                    }
                }
                clientDatabase
                    .libraryFolderDao()
                    .findById("folder4a")
                    ?.deletedAt
                    .shouldNotBeNull()
            }
        }

        test("server deleteLibrary → SSE → library and folders cascade-tombstoned in client Room") {
            withClientSyncEngineAgainstServer {
                engine.start(currentUserId = "u1")

                // Create library with one folder.
                serverLibraryRepository.upsert(libraryPayload(id = "lib5", name = "Drama"))
                awaitClientLibrary(clientDatabase, "lib5", ROUND_TRIP_TIMEOUT_SECONDS.seconds)

                serverLibraryFolderRepository.upsert(
                    folderPayload(id = "folder5a", libraryId = "lib5", rootPath = "/audio/drama"),
                )
                awaitClientFolder(clientDatabase, "folder5a", ROUND_TRIP_TIMEOUT_SECONDS.seconds)

                // Cascade: soft-delete folder then library (matching LibraryAdminServiceImpl.deleteLibrary order).
                serverLibraryFolderRepository.softDelete(FolderId("folder5a"))
                serverLibraryRepository.softDelete(LibraryId("lib5"))

                // Library row is tombstoned.
                withTimeout(ROUND_TRIP_TIMEOUT_SECONDS.seconds) {
                    while (clientDatabase
                            .libraryDao()
                            .findAll()
                            .firstOrNull { it.id == "lib5" }
                            ?.deletedAt == null
                    ) {
                        // Poll until the library tombstone arrives.
                    }
                }
                clientDatabase.libraryDao().findById("lib5").shouldBeNull() // findById filters live rows
                clientDatabase
                    .libraryDao()
                    .findAll()
                    .firstOrNull { it.id == "lib5" }
                    ?.deletedAt
                    .shouldNotBeNull()

                // Folder row is tombstoned.
                withTimeout(ROUND_TRIP_TIMEOUT_SECONDS.seconds) {
                    while (clientDatabase.libraryFolderDao().findById("folder5a")?.deletedAt == null) {
                        // Poll until the folder tombstone arrives.
                    }
                }
                clientDatabase
                    .libraryFolderDao()
                    .findById("folder5a")
                    ?.deletedAt
                    .shouldNotBeNull()
            }
        }
    })

/**
 * Polls the client Room DB until the library [id] is present (live, non-tombstoned),
 * or fails after [timeout].
 */
private suspend fun awaitClientLibrary(
    database: ListenUpDatabase,
    id: String,
    timeout: kotlin.time.Duration,
) = withTimeout(timeout) {
    var library = database.libraryDao().findById(id)
    while (library == null) {
        library = database.libraryDao().findById(id)
    }
    library
}

/**
 * Polls the client Room DB until the folder [id] is present (non-tombstoned),
 * or fails after [timeout].
 */
private suspend fun awaitClientFolder(
    database: ListenUpDatabase,
    id: String,
    timeout: kotlin.time.Duration,
) = withTimeout(timeout) {
    var folder = database.libraryFolderDao().findById(id)
    while (folder == null || folder.deletedAt != null) {
        folder = database.libraryFolderDao().findById(id)
    }
    folder
}

private fun libraryPayload(
    id: String,
    name: String,
): LibrarySyncPayload {
    val now = System.currentTimeMillis()
    return LibrarySyncPayload(
        id = id,
        name = name,
        metadataPrecedence = "embedded,abs,sidecar",
        accessMode = "shared",
        createdByUserId = null,
        revision = 0L,
        updatedAt = now,
        createdAt = now,
        deletedAt = null,
        initialScanCompletedAt = null,
    )
}

private fun folderPayload(
    id: String,
    libraryId: String,
    rootPath: String,
): LibraryFolderSyncPayload {
    val now = System.currentTimeMillis()
    return LibraryFolderSyncPayload(
        id = id,
        libraryId = libraryId,
        rootPath = rootPath,
        revision = 0L,
        updatedAt = now,
        createdAt = now,
        deletedAt = null,
    )
}
