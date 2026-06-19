package com.calypsan.listenup.server.scanner.watcher

import app.cash.turbine.test
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.div
import kotlin.io.path.writeBytes
import kotlin.time.Duration.Companion.seconds

/**
 * Real-filesystem tests for the JVM-native recursive watcher. Linux-only in CI
 * (inotify); the Windows/macOS WatchService backends behave equivalently but are
 * a developer-machine concern.
 */
class RecursiveDirectoryWatcherTest :
    FunSpec({

        val isLinux = System.getProperty("os.name").lowercase().contains("linux")

        if (!isLinux) {
            test("RecursiveDirectoryWatcher tests are Linux-only — skipping suite") { /* no-op */ }
        } else {
            test("emits a Create when a file appears in a watched directory") {
                runBlocking {
                    val tmp = Files.createTempDirectory("rdw-")
                    try {
                        withWatcher { watcher ->
                            watcher.add(tmp.toString())
                            watcher.onEventFlow.test(timeout = 5.seconds) {
                                (tmp / "track.mp3").writeBytes(byteArrayOf(1))
                                val ev = awaitFor(tmp)
                                ev.kind shouldBe DirectoryWatchEventKind.Create
                                ev.path shouldBe (tmp / "track.mp3").toString()
                                cancelAndIgnoreRemainingEvents()
                            }
                        }
                    } finally {
                        tmp.toFile().deleteRecursively()
                    }
                }
            }

            // THE REGRESSION: kfswatch capped at 63. Register 100 dirs and prove the
            // 100th still fires — this is exactly what silently failed before.
            test("watches far more than kfswatch's 63-target cap — the 100th dir still fires") {
                runBlocking {
                    val tmp = Files.createTempDirectory("rdw-cap-")
                    try {
                        withWatcher { watcher ->
                            val dirs = (1..100).map { (tmp / "dir-%03d".format(it)).apply { createDirectories() } }
                            dirs.forEach { watcher.add(it.toString()) }
                            val target = dirs.last()
                            watcher.onEventFlow.test(timeout = 5.seconds) {
                                (target / "file.mp3").writeBytes(byteArrayOf(1))
                                awaitFor(target).kind shouldBe DirectoryWatchEventKind.Create
                                cancelAndIgnoreRemainingEvents()
                            }
                        }
                    } finally {
                        tmp.toFile().deleteRecursively()
                    }
                }
            }

            // A new subdir whose file already exists at registration time: the file
            // predates the watch, so only a synthetic Create can surface it.
            test("surfaces a file already inside a newly-created subdirectory") {
                runBlocking {
                    val tmp = Files.createTempDirectory("rdw-race-")
                    try {
                        withWatcher { watcher ->
                            watcher.add(tmp.toString())
                            watcher.onEventFlow.test(timeout = 5.seconds) {
                                val book = (tmp / "New Book").apply { createDirectories() }
                                (book / "audio.m4b").writeBytes(byteArrayOf(1, 2, 3))
                                var sawFile = false
                                run {
                                    repeat(20) {
                                        val ev = awaitItem()
                                        if (ev.path == (book / "audio.m4b").toString() &&
                                            ev.kind == DirectoryWatchEventKind.Create
                                        ) {
                                            sawFile = true
                                            return@run
                                        }
                                    }
                                }
                                sawFile shouldBe true
                                cancelAndIgnoreRemainingEvents()
                            }
                        }
                    } finally {
                        tmp.toFile().deleteRecursively()
                    }
                }
            }

            test("emits a Delete when a watched file is removed") {
                runBlocking {
                    val tmp = Files.createTempDirectory("rdw-del-")
                    try {
                        val f = (tmp / "gone.mp3").apply { writeBytes(byteArrayOf(1)) }
                        withWatcher { watcher ->
                            watcher.add(tmp.toString())
                            watcher.onEventFlow.test(timeout = 5.seconds) {
                                Files.delete(f)
                                var sawDelete = false
                                run {
                                    repeat(20) {
                                        val ev = awaitItem()
                                        if (ev.path == f.toString() && ev.kind == DirectoryWatchEventKind.Delete) {
                                            sawDelete = true
                                            return@run
                                        }
                                    }
                                }
                                sawDelete shouldBe true
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

private suspend fun withWatcher(block: suspend (RecursiveDirectoryWatcher) -> Unit) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val watcher = RecursiveDirectoryWatcher(scope)
    try {
        block(watcher)
    } finally {
        watcher.close()
        scope.cancel()
    }
}

/** Awaits the next event whose targetDirectory is [dir], skipping unrelated noise. */
private suspend fun app.cash.turbine.ReceiveTurbine<DirectoryWatchEvent>.awaitFor(dir: Path): DirectoryWatchEvent {
    repeat(20) {
        val ev = awaitItem()
        if (ev.targetDirectory == dir.toString()) return ev
    }
    error("no event for $dir within 20 items")
}
