package com.calypsan.listenup.server.scanner.watcher

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.writeString
import kotlin.random.Random
import kotlin.test.Test
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Native runtime proof for [InotifyDirectoryWatcher] — the inotify-backed [LowLevelDirectoryWatcher]
 * peer of the JVM `RecursiveDirectoryWatcher`. Watches a directory, then creates / modifies / deletes
 * a file inside it and asserts the kernel events surface as Create / Modify / Delete (`MOVED_TO`/
 * `MOVED_FROM` map to Create/Delete the same way the JVM WatchService does).
 *
 * Scratch dir is working-directory-relative (`SystemTemporaryDirectory` is unusable in the linuxX64
 * test runner). `onSubscription` makes the collector's subscription deterministic so the first events
 * after `add()` are never raced away by the replay-less SharedFlow.
 */
class InotifyDirectoryWatcherNativeTest {
    @Test
    fun surfacesCreateModifyDeleteForFilesInAWatchedDirectory(): Unit =
        runBlocking {
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
                val file = Path(dir, "book.mp3")

                SystemFileSystem.sink(file).buffered().use { it.writeString("hello") }
                events.await { it.kind == DirectoryWatchEventKind.Create && it.path.endsWith("book.mp3") }
                events.await { it.kind == DirectoryWatchEventKind.Modify && it.path.endsWith("book.mp3") }

                SystemFileSystem.delete(file)
                val delete = events.await { it.kind == DirectoryWatchEventKind.Delete && it.path.endsWith("book.mp3") }
                delete.targetDirectory shouldBe dir.toString()
            } finally {
                collector.cancel()
                watcher.close()
                scope.cancel()
                SystemFileSystem.delete(dir, mustExist = false)
            }
        }

    private suspend fun Channel<DirectoryWatchEvent>.await(
        predicate: (DirectoryWatchEvent) -> Boolean,
    ): DirectoryWatchEvent =
        withTimeout(AWAIT_TIMEOUT) {
            var event = receive()
            while (!predicate(event)) event = receive()
            event
        }

    private companion object {
        const val HEX_RADIX = 16
        val SUBSCRIBE_DELAY = 300.milliseconds
        val AWAIT_TIMEOUT = 5.seconds
    }
}
