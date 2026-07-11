package com.calypsan.listenup.server.librarywrite

import com.calypsan.listenup.server.scanner.watcher.FolderWatcher
import com.calypsan.listenup.server.scanner.watcher.StableSizeDebouncer
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.io.buffered
import kotlinx.io.files.SystemFileSystem
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.io.files.Path as IoPath
import kotlin.io.path.createDirectories
import kotlin.io.path.div
import kotlin.time.Duration.Companion.milliseconds

/**
 * The load-bearing integration test of the LibraryWriteBroker phase: a REAL [FolderWatcher]
 * (real `LowLevelDirectoryWatcher`, real inotify on Linux CI) watching a temp library while the
 * broker writes into it. A broker write must never wake the watcher; an external write to the
 * very same path must. Linux-only, same as [com.calypsan.listenup.server.scanner.watcher.FolderWatcherTest]
 * — CI runs Linux, macOS verification is a developer-machine task.
 *
 * The broker under test uses a short suppression TTL (500 ms) so the external-write control
 * probe isn't swallowed by the leftover claim from the broker's own write.
 */
class WatcherSuppressionTest :
    FunSpec({

        val isLinux = System.getProperty("os.name").lowercase().contains("linux")

        if (!isLinux) {
            test("watcher suppression integration tests are Linux-only — skipping suite") { /* no-op */ }
        } else {
            test("broker writeFile never wakes the watcher; an external write to the same path does") {
                runBlocking {
                    val tmp = Files.createTempDirectory("listenup-suppression-")
                    val bookDir = (tmp / "Author/Title").apply { createDirectories() }
                    val target = IoPath(bookDir.toString(), "listenup.json")
                    val registry = SelfWriteRegistry { System.currentTimeMillis() }
                    val broker =
                        LibraryWriteBroker(
                            registry = registry,
                            journal = WriteJournal(tempJournalDir()),
                            suppressionTtlMs = 500,
                        )
                    try {
                        withSuppressingWatcher(tmp, registry) { watcher ->
                            val emissions = mutableListOf<IoPath>()
                            val collector =
                                launch(start = CoroutineStart.UNDISPATCHED) {
                                    watcher.events.collect { emissions.add(it) }
                                }

                            broker.writeFile(target, "{\"schemaVersion\":1}".encodeToByteArray())
                            delay(1_500) // generous: settle window + inotify latency + TTL expiry

                            withClue("broker self-write must be suppressed. emissions: $emissions") {
                                emissions.size shouldBe 0
                            }

                            // Control: the SAME path written externally (claim now expired) DOES fire.
                            SystemFileSystem.sink(target).buffered().use {
                                it.write("{\"edited\":true}".encodeToByteArray())
                            }
                            delay(1_500)

                            withClue("external write must reach the watcher. emissions: $emissions") {
                                emissions shouldContain IoPath(bookDir.toString())
                            }

                            collector.cancel()
                        }
                    } finally {
                        tmp.toFile().deleteRecursively()
                    }
                }
            }

            test("broker writeFile that creates its parent directory is fully suppressed too") {
                runBlocking {
                    val tmp = Files.createTempDirectory("listenup-suppression-dir-")
                    (tmp / "Author").createDirectories()
                    // "NewBook" does NOT exist — the broker's createDirectories makes it, which
                    // fires a directory-Create event that must also be suppressed.
                    val target = IoPath(tmp.toString(), "Author", "NewBook", "listenup.json")
                    val registry = SelfWriteRegistry { System.currentTimeMillis() }
                    val broker =
                        LibraryWriteBroker(
                            registry = registry,
                            journal = WriteJournal(tempJournalDir()),
                            suppressionTtlMs = 500,
                        )
                    try {
                        withSuppressingWatcher(tmp, registry) { watcher ->
                            val emissions = mutableListOf<IoPath>()
                            val collector =
                                launch(start = CoroutineStart.UNDISPATCHED) {
                                    watcher.events.collect { emissions.add(it) }
                                }

                            broker.writeFile(target, byteArrayOf(1))
                            delay(1_500)

                            withClue("directory-create from the broker must be suppressed. emissions: $emissions") {
                                emissions.size shouldBe 0
                            }

                            collector.cancel()
                        }
                    } finally {
                        tmp.toFile().deleteRecursively()
                    }
                }
            }

            test("multiple kernel events for one registered path are ALL swallowed") {
                runBlocking {
                    val tmp = Files.createTempDirectory("listenup-suppression-multi-")
                    val bookDir = (tmp / "Author/Title").apply { createDirectories() }
                    val target = IoPath(bookDir.toString(), "track.mp3")
                    val registry = SelfWriteRegistry { System.currentTimeMillis() }
                    try {
                        withSuppressingWatcher(tmp, registry) { watcher ->
                            val emissions = mutableListOf<IoPath>()
                            val collector =
                                launch(start = CoroutineStart.UNDISPATCHED) {
                                    watcher.events.collect { emissions.add(it) }
                                }

                            // One registration, then a create + two modifies: a real write produces
                            // several kernel events, and every one of them must be swallowed (this
                            // is why intake checks isSelfWrite, not consume-on-first-match).
                            registry.register(target, ttlMs = 30_000)
                            SystemFileSystem.sink(target).buffered().use { it.write(byteArrayOf(1)) }
                            delay(100)
                            SystemFileSystem.sink(target).buffered().use { it.write(byteArrayOf(1, 2)) }
                            delay(100)
                            SystemFileSystem.sink(target).buffered().use { it.write(byteArrayOf(1, 2, 3)) }
                            delay(1_500)

                            withClue("every event of a registered path must be swallowed. emissions: $emissions") {
                                emissions.size shouldBe 0
                            }

                            collector.cancel()
                        }
                    } finally {
                        tmp.toFile().deleteRecursively()
                    }
                }
            }
        }
    })

/** Starts a real [FolderWatcher] over [libraryRoot] consulting [registry], with a fast test debouncer. */
private suspend fun withSuppressingWatcher(
    libraryRoot: Path,
    registry: SelfWriteRegistry,
    block: suspend (FolderWatcher) -> Unit,
) {
    val supervisor = SupervisorJob()
    val scope = CoroutineScope(supervisor + Dispatchers.Default)
    val watcher =
        FolderWatcher(
            libraryRoot = IoPath(libraryRoot.toString()),
            scope = scope,
            debouncer = StableSizeDebouncer(settle = 50.milliseconds, poll = 25.milliseconds),
            selfWrites = registry,
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
