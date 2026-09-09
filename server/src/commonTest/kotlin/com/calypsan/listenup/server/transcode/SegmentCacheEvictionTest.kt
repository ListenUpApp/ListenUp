package com.calypsan.listenup.server.transcode

import com.calypsan.listenup.server.io.deleteRecursively
import com.calypsan.listenup.server.io.writeBytes
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory
import kotlin.random.Random

/**
 * The cap on [TranscodeSettings.cacheCapBytes] is a promise the segment cache used to make and never
 * keep: eviction existed but nothing called it. These pin the sweep that now keeps it — least
 * recently written file first, a running session's file never, and once at boot.
 *
 * `commonTest`, so the same rules hold on the native binary that actually ships. No virtual time
 * here (no `kotlinx-coroutines-test` on this source set), so the boot-sweep case polls the disk on a
 * real dispatcher with a bounded timeout rather than advancing a clock.
 */
class SegmentCacheEvictionTest :
    FunSpec({

        test("a sweep under the cap evicts nothing") {
            val fixture = evictionFixture(capBytes = 1L shl 30)
            fixture.writeSegment("b1", "f1", bytes = 4096)
            fixture.writeSegment("b2", "f2", bytes = 4096)

            fixture.engine.sweepCache()

            fixture.cache.has("b1", "f1", 0) shouldBe true
            fixture.cache.has("b2", "f2", 0) shouldBe true
            fixture.cleanUp()
        }

        test("a sweep over the cap evicts the least-recently-touched file dir first") {
            val fixture = evictionFixture(capBytes = 6000)
            fixture.writeSegment("b1", "f1", bytes = 4096)
            fixture.writeSegmentNewerThan("b2", "f2", bytes = 4096, olderBookId = "b1", olderFileId = "f1")

            fixture.engine.sweepCache()

            fixture.cache.has("b1", "f1", 0) shouldBe false
            fixture.cache.has("b2", "f2", 0) shouldBe true
            fixture.cleanUp()
        }

        // The listener is on this file right now and FFmpeg is still writing into it. Deleting it
        // from under them would hand the player a 404 mid-book — the cap is not worth that.
        test("a sweep never evicts a directory whose session is running") {
            val fixture = evictionFixture(capBytes = 1)
            fixture.engine
                .ensureRunning(RUNNING_SESSION, fromSegment = 0)
                .shouldBeInstanceOf<SessionAdmission.Admitted>()
            fixture.writeSegment("b1", "f1", bytes = 4096)
            fixture.writeSegmentNewerThan("b2", "f2", bytes = 4096, olderBookId = "b1", olderFileId = "f1")

            fixture.engine.sweepCache()

            fixture.cache.has("b1", "f1", 0) shouldBe true
            fixture.cache.has("b2", "f2", 0) shouldBe false
            fixture.cleanUp()
        }

        test("a zero cap means transcoding is off, and the sweep leaves whatever is on disk alone") {
            val fixture = evictionFixture(capBytes = 0)
            fixture.writeSegment("b1", "f1", bytes = 4096)

            fixture.engine.sweepCache()

            fixture.cache.has("b1", "f1", 0) shouldBe true
            fixture.cleanUp()
        }

        // A server that filled its cache and restarted must reclaim the space at once, not a sweep
        // interval later.
        test("a boot sweep removes directories over the cap") {
            val fixture = evictionFixture(capBytes = 1)
            fixture.writeSegment("b1", "f1", bytes = 4096)
            val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

            val watchdog = fixture.engine.startWatchdog(scope)
            try {
                withTimeout(BOOT_SWEEP_TIMEOUT_MS) {
                    while (fixture.cache.has("b1", "f1", 0)) delay(POLL_MS)
                }
            } finally {
                watchdog.cancel()
                scope.cancel()
            }

            fixture.cache.has("b1", "f1", 0) shouldBe false
            fixture.cleanUp()
        }
    })

private const val BOOT_SWEEP_TIMEOUT_MS = 5_000L
private const val POLL_MS = 10L

/** How many rewrites to attempt before giving up on producing a strictly newer mtime. */
private const val MTIME_ATTEMPTS = 200

private val RUNNING_SESSION =
    TranscodeSession(
        bookId = "b1",
        fileId = "f1",
        sourcePath = "/x/a.m4b",
        sampleRate = 44_100,
        durationMs = 3_600_000,
    )

/** A spawner that records nothing and spawns nothing — the engine only needs it to return. */
private object NoopSpawner : TranscodeSpawner {
    override suspend fun start(commands: List<List<String>>) = Unit

    override fun stop() = Unit
}

private class EvictionFixture(
    val engine: TranscodeSessionEngine,
    val cache: SegmentCache,
    private val dir: Path,
) {
    /** Writes one [bytes]-long segment for `{bookId}/{fileId}`, creating the directory layout first. */
    suspend fun writeSegment(
        bookId: String,
        fileId: String,
        bytes: Int,
    ) {
        cache.prepareDir(bookId, fileId)
        cache.segmentPath(bookId, fileId, 0).writeBytes(ByteArray(bytes))
    }

    /**
     * Like [writeSegment], but guaranteed to read as more recently touched than the older directory
     * by the cache's own recency measure. Filesystems report mtime at millisecond (or coarser)
     * resolution, so two writes in the same tick would otherwise be indistinguishable; rewriting
     * with a short pause until the cache sees the difference makes the ordering real, not assumed.
     */
    suspend fun writeSegmentNewerThan(
        bookId: String,
        fileId: String,
        bytes: Int,
        olderBookId: String,
        olderFileId: String,
    ) {
        repeat(MTIME_ATTEMPTS) {
            writeSegment(bookId, fileId, bytes)
            val listed = cache.listCachedFiles().associateBy { it.bookId to it.fileId }
            val newer = checkNotNull(listed[bookId to fileId]).lastTouchedMs
            val older = checkNotNull(listed[olderBookId to olderFileId]).lastTouchedMs
            if (newer > older) return
            delay(POLL_MS)
        }
        error("could not write $bookId/$fileId with an mtime newer than $olderBookId/$olderFileId")
    }

    fun cleanUp() = deleteRecursively(dir)
}

private fun evictionFixture(capBytes: Long): EvictionFixture {
    val dir = Path(SystemTemporaryDirectory, "segcache-evict-${Random.nextLong().toString(16)}")
    SystemFileSystem.createDirectories(dir)
    val cache = SegmentCache(dir)
    val engine =
        TranscodeSessionEngine(
            ffmpegPath = { "/usr/bin/ffmpeg" },
            cache = cache,
            settings = TranscodeSettings(cacheCapBytes = capBytes),
            newSpawner = { NoopSpawner },
        )
    return EvictionFixture(engine, cache, dir)
}
