package com.calypsan.listenup.server.scanner.watcher

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.writeString
import kotlin.random.Random
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Native runtime proof for [InotifyDirectoryWatcher] — the inotify-backed [LowLevelDirectoryWatcher]
 * peer of the JVM `RecursiveDirectoryWatcher`. Watches a directory, then creates / modifies / deletes
 * a file inside it and asserts the kernel events surface as Create / Modify / Delete (`MOVED_TO`/
 * `MOVED_FROM` map to Create/Delete the same way the JVM WatchService does).
 *
 * Scratch dir is working-directory-relative. `onSubscription` makes the collector's subscription
 * deterministic so the first events after `add()` are never raced away by the replay-less SharedFlow.
 */
class InotifyDirectoryWatcherNativeTest :
    FunSpec({
        test("surfaces create modify delete for files in a watched directory") {
            val dir = Path("lu-inotify-test-${Random.nextInt(1, Int.MAX_VALUE).toString(HEX_RADIX)}")
            SystemFileSystem.createDirectories(dir)
            val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
            val watcher = InotifyDirectoryWatcher(scope)
            val events = Channel<DirectoryWatchEvent>(Channel.UNLIMITED)
            val collector: Job =
                scope.launch { watcher.onEventFlow.collect { events.send(it) } }
            try {
                // Let the replay-less SharedFlow collector subscribe before the watcher emits.
                delay(SUBSCRIBE_DELAY)
                watcher.add(dir.toString())
                val file = Path(dir, WATCHED_FILE_NAME)

                SystemFileSystem.sink(file).buffered().use { it.writeString("hello") }
                events.await { it.kind == DirectoryWatchEventKind.Create && it.path.endsWith(WATCHED_FILE_NAME) }
                events.await { it.kind == DirectoryWatchEventKind.Modify && it.path.endsWith(WATCHED_FILE_NAME) }

                SystemFileSystem.delete(file)
                val delete =
                    events.await {
                        it.kind == DirectoryWatchEventKind.Delete && it.path.endsWith(WATCHED_FILE_NAME)
                    }
                delete.targetDirectory shouldBe dir.toString()
            } finally {
                collector.cancel()
                watcher.close()
                scope.cancel()
                SystemFileSystem.delete(dir, mustExist = false)
            }
        }
    })

private suspend fun Channel<DirectoryWatchEvent>.await(
    predicate: (DirectoryWatchEvent) -> Boolean,
): DirectoryWatchEvent =
    withTimeout(AWAIT_TIMEOUT) {
        var event = receive()
        while (!predicate(event)) event = receive()
        event
    }

private const val HEX_RADIX = 16
private const val WATCHED_FILE_NAME = "book.mp3"
private val SUBSCRIBE_DELAY = 300.milliseconds
private val AWAIT_TIMEOUT = 5.seconds
