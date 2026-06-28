package com.calypsan.listenup.server.scanner.watcher

import app.cash.turbine.test
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.io.files.Path as IoPath
import kotlin.io.path.createDirectories
import kotlin.io.path.div
import kotlin.io.path.writeBytes
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Linux-flavored integration tests for FolderWatcher. CI runs on Linux so
 * these exercise inotify; on macOS the same tests should pass against
 * FSEvents but verifying that is a developer-machine task. The debouncer
 * uses short settle/poll values (50 ms / 25 ms) to keep wall-clock cost
 * per test under a second.
 *
 * **Test pattern:** the directory tree is created BEFORE the watcher is
 * started, so the initial walk-and-register picks up every directory.
 * Tests then write/modify files in the existing tree to trigger events.
 */
class FolderWatcherTest :
    FunSpec({

        val isLinux = System.getProperty("os.name").lowercase().contains("linux")

        if (!isLinux) {
            test("FolderWatcher integration tests are Linux-only — skipping suite") { /* no-op */ }
        } else {
            test("emits the book root after a file is created in a watched directory") {
                runBlocking {
                    val tmp = Files.createTempDirectory("listenup-watcher-")
                    val bookDir = (tmp / "Author/Title").apply { createDirectories() }
                    try {
                        withWatcher(tmp) { watcher ->
                            watcher.events.test(timeout = 5.seconds) {
                                (bookDir / "track.mp3").writeBytes(byteArrayOf(1, 2, 3))
                                awaitItem() shouldBe IoPath(bookDir.toString())
                                cancelAndIgnoreRemainingEvents()
                            }
                        }
                    } finally {
                        tmp.toFile().deleteRecursively()
                    }
                }
            }

            test("multi-disc subdirectory emits the parent book root, not the disc folder") {
                runBlocking {
                    val tmp = Files.createTempDirectory("listenup-watcher-")
                    val bookDir = (tmp / "Author/Multi-Disc Book").apply { createDirectories() }
                    val cd1 = (bookDir / "CD1").apply { createDirectories() }
                    try {
                        withWatcher(tmp) { watcher ->
                            watcher.events.test(timeout = 5.seconds) {
                                (cd1 / "track.mp3").writeBytes(byteArrayOf(1, 2, 3))
                                awaitItem() shouldBe IoPath(bookDir.toString())
                                cancelAndIgnoreRemainingEvents()
                            }
                        }
                    } finally {
                        tmp.toFile().deleteRecursively()
                    }
                }
            }

            test("rapid bursts on the same book root coalesce to a single emission") {
                runBlocking {
                    val tmp = Files.createTempDirectory("listenup-watcher-")
                    val bookDir = (tmp / "Author/Burst Book").apply { createDirectories() }
                    try {
                        withWatcher(tmp) { watcher ->
                            val emissions = mutableListOf<IoPath>()
                            val collectorJob =
                                launch(start = CoroutineStart.UNDISPATCHED) {
                                    watcher.events.collect { emissions.add(it) }
                                }

                            // Write 20 files in rapid succession. Per-book
                            // coalescing should produce exactly one emission.
                            repeat(20) { i ->
                                (bookDir / "track-%02d.mp3".format(i)).writeBytes(byteArrayOf(i.toByte()))
                            }

                            delay(400) // settle window + buffer for all 20 writes
                            collectorJob.cancel()

                            withClue("emissions: $emissions") {
                                emissions.count { it == IoPath(bookDir.toString()) } shouldBe 1
                            }
                        }
                    } finally {
                        tmp.toFile().deleteRecursively()
                    }
                }
            }

            test("dotfiles do not trigger emissions") {
                runBlocking {
                    val tmp = Files.createTempDirectory("listenup-watcher-")
                    val bookDir = (tmp / "Author/Title").apply { createDirectories() }
                    try {
                        withWatcher(tmp) { watcher ->
                            val emissions = mutableListOf<IoPath>()
                            val collectorJob =
                                launch(start = CoroutineStart.UNDISPATCHED) {
                                    watcher.events.collect { emissions.add(it) }
                                }

                            (bookDir / ".DS_Store").writeBytes(byteArrayOf(0))
                            delay(150)

                            withClue("dotfile must not produce any emission. emissions: $emissions") {
                                emissions.size shouldBe 0
                            }

                            collectorJob.cancel()
                        }
                    } finally {
                        tmp.toFile().deleteRecursively()
                    }
                }
            }

            test("a book under the 100th directory still emits its book root (no 63-dir cap)") {
                runBlocking {
                    val tmp = Files.createTempDirectory("listenup-watcher-cap-")
                    // 99 filler author dirs walked first, then the real one — past kfswatch's 63.
                    (1..99).forEach { (tmp / "Filler-%03d".format(it) / "Book").createDirectories() }
                    val bookDir = (tmp / "ZZZ Author/Late Book").apply { createDirectories() }
                    try {
                        withWatcher(tmp) { watcher ->
                            watcher.events.test(timeout = 5.seconds) {
                                (bookDir / "track.mp3").writeBytes(byteArrayOf(1, 2, 3))
                                awaitItem() shouldBe IoPath(bookDir.toString())
                                cancelAndIgnoreRemainingEvents()
                            }
                        }
                    } finally {
                        tmp.toFile().deleteRecursively()
                    }
                }
            }
        }
    })

private suspend fun withWatcher(
    libraryRoot: Path,
    block: suspend (FolderWatcher) -> Unit,
) {
    val supervisor = SupervisorJob()
    val scope = CoroutineScope(supervisor + Dispatchers.Default)
    val watcher =
        FolderWatcher(
            libraryRoot = IoPath(libraryRoot.toString()),
            scope = scope,
            debouncer = StableSizeDebouncer(settle = 50.milliseconds, poll = 25.milliseconds),
        )
    try {
        watcher.start()
        delay(150) // give inotify time to attach watches across the tree
        block(watcher)
    } finally {
        watcher.close()
        scope.cancel()
    }
}
