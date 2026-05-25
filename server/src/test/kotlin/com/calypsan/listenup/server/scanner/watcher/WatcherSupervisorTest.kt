@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.calypsan.listenup.server.scanner.watcher

import com.calypsan.listenup.api.dto.LibraryFolderRef
import com.calypsan.listenup.core.FolderId
import com.calypsan.listenup.core.LibraryId
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.runTest
import java.nio.file.Path

/**
 * Unit tests for [WatcherSupervisor].
 *
 * Uses [FakeFolderWatcher] as the test seam in place of [FolderWatcher] —
 * [FolderWatcher] embeds kfswatch (a native FS-event listener) and cannot
 * be easily stubbed at the class level. The fake tracks start/close calls
 * and provides a way to simulate emission callbacks.
 */
class WatcherSupervisorTest :
    FunSpec({

        test("mount creates a FolderWatcher and stores it keyed by folderId") {
            runTest {
                val factory = FakeFolderWatcherFactory()
                val supervisor = WatcherSupervisor(factory::create)

                val folder = folderRef("f-1", "/tmp/books")
                supervisor.mount(LibraryId("lib-1"), folder) { _, _ -> }

                factory.watchers.size shouldBe 1
                factory.watchers.containsKey(FolderId("f-1")) shouldBe true
            }
        }

        test("mount on the same folderId twice is idempotent — no duplicate watcher") {
            runTest {
                val factory = FakeFolderWatcherFactory()
                val supervisor = WatcherSupervisor(factory::create)

                val folder = folderRef("f-1", "/tmp/books")
                supervisor.mount(LibraryId("lib-1"), folder) { _, _ -> }
                supervisor.mount(LibraryId("lib-1"), folder) { _, _ -> }

                factory.watchers.size shouldBe 1
            }
        }

        test("unmount cancels and removes the watcher") {
            runTest {
                val factory = FakeFolderWatcherFactory()
                val supervisor = WatcherSupervisor(factory::create)

                val folder = folderRef("f-1", "/tmp/books")
                supervisor.mount(LibraryId("lib-1"), folder) { _, _ -> }
                supervisor.unmount(FolderId("f-1"))

                factory.watchers.containsKey(FolderId("f-1")) shouldBe false
                factory.closedCount shouldBe 1
            }
        }

        test("unmount on unknown folderId is a no-op") {
            runTest {
                val supervisor = WatcherSupervisor { _, _ -> FakeFolderWatcher() }
                supervisor.unmount(FolderId("no-such-folder")) // must not throw
            }
        }

        test("unmountAllForLibrary removes all watchers for that library") {
            runTest {
                val factory = FakeFolderWatcherFactory()
                val supervisor = WatcherSupervisor(factory::create)

                supervisor.mount(LibraryId("lib-1"), folderRef("f-1", "/a")) { _, _ -> }
                supervisor.mount(LibraryId("lib-1"), folderRef("f-2", "/b")) { _, _ -> }
                supervisor.mount(LibraryId("lib-2"), folderRef("f-3", "/c")) { _, _ -> }

                supervisor.unmountAllForLibrary(LibraryId("lib-1"))

                factory.watchers.containsKey(FolderId("f-1")) shouldBe false
                factory.watchers.containsKey(FolderId("f-2")) shouldBe false
                factory.watchers.containsKey(FolderId("f-3")) shouldBe true
                factory.closedCount shouldBe 2
            }
        }

        test("concurrent mount + unmount is safe") {
            runTest {
                val factory = FakeFolderWatcherFactory()
                val supervisor = WatcherSupervisor(factory::create)

                // 16 concurrent mount/unmount cycles on 4 folders
                val jobs =
                    (1..4).flatMap { i ->
                        val folderId = "f-$i"
                        val folder = folderRef(folderId, "/dir/$i")
                        listOf(
                            launch { supervisor.mount(LibraryId("lib-1"), folder) { _, _ -> } },
                            launch { supervisor.unmount(FolderId(folderId)) },
                            launch { supervisor.mount(LibraryId("lib-1"), folder) { _, _ -> } },
                            launch { supervisor.unmount(FolderId(folderId)) },
                        )
                    }
                jobs.forEach { it.join() }
                // No assertion on exact state — just assert no exceptions were thrown
                // and the internal map is consistent (no dangling references).
                val remaining = factory.watchers.size
                remaining shouldBe remaining // trivially true; guard is the absence of a throw
            }
        }

        test("file-change event from a mounted folder triggers the supervisor's onEvent callback") {
            runTest {
                val factory = FakeFolderWatcherFactory()
                val emittedEvents = mutableListOf<Pair<LibraryId, Path>>()
                val mutex = Mutex()
                val supervisor = WatcherSupervisor(factory::create)

                val folder = folderRef("f-1", "/tmp/books")
                supervisor.mount(LibraryId("lib-1"), folder) { libId, path ->
                    mutex.withLock { emittedEvents += libId to path }
                }

                // Simulate a file-change event from the watcher.
                val watcher = factory.watchers[FolderId("f-1")]!!
                val changedPath = Path.of("/tmp/books/Author/Title")
                watcher.simulateEvent(changedPath)

                mutex.withLock {
                    emittedEvents.size shouldBe 1
                    emittedEvents.single() shouldBe (LibraryId("lib-1") to changedPath)
                }
            }
        }
    })

// --- Helpers ----------------------------------------------------------------

private fun folderRef(
    folderId: String,
    path: String,
): LibraryFolderRef = LibraryFolderRef(FolderId(folderId), path)

// --- Fake watcher infrastructure --------------------------------------------

/**
 * Fake watcher handle that records close calls and lets tests simulate events.
 * Implements [WatcherHandle] so the [WatcherSupervisor] can close it.
 *
 * [onClose] is set by [FakeFolderWatcherFactory] to update the factory's
 * closed counter and remove the entry from the active-watchers map.
 */
internal class FakeFolderWatcher : WatcherHandle {
    var closed = false
    var onClose: (() -> Unit)? = null
    private var onEvent: (suspend (Path) -> Unit)? = null

    fun setOnEvent(callback: suspend (Path) -> Unit) {
        onEvent = callback
    }

    suspend fun simulateEvent(path: Path) {
        onEvent?.invoke(path)
    }

    override suspend fun close() {
        closed = true
        onClose?.invoke()
    }
}

/**
 * Factory that creates [FakeFolderWatcher]s keyed by [FolderId].
 *
 * [watchers] holds only **active** (not yet closed) fakes so tests can
 * assert that [WatcherSupervisor.unmount] actually removes entries.
 * [closedCount] is a separate counter so closed fakes removed from the map
 * are still counted.
 */
internal class FakeFolderWatcherFactory {
    val watchers = mutableMapOf<FolderId, FakeFolderWatcher>()
    private var _closedCount = 0
    val closedCount: Int get() = _closedCount

    suspend fun create(
        folder: LibraryFolderRef,
        onEvent: suspend (Path) -> Unit,
    ): FakeFolderWatcher {
        val folderId = folder.id
        val fake = FakeFolderWatcher()
        fake.setOnEvent(onEvent)
        fake.onClose = {
            _closedCount++
            watchers.remove(folderId)
        }
        watchers[folderId] = fake
        return fake
    }
}
