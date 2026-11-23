package com.calypsan.listenup.server.transcode

import com.calypsan.listenup.server.io.deleteRecursively
import com.calypsan.listenup.server.io.writeBytes
import com.calypsan.listenup.server.io.writeText
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.longs.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory
import kotlin.random.Random

class SegmentCacheTest :
    FunSpec({

        fun tempDir(): Path =
            Path(SystemTemporaryDirectory, "segcache-${Random.nextLong().toString(16)}")
                .also { SystemFileSystem.createDirectories(it) }

        test("segments live under book and file, so a whole file can be evicted at once") {
            val dir = tempDir()
            val cache = SegmentCache(dir)

            cache.segmentPath("b1", "f1", 7).toString() shouldBe
                Path(dir, "b1/f1/seg/aac-64k/seg00007.aac").toString()

            deleteRecursively(dir)
        }

        // FFmpeg writes the names; segmentPath looks them up. If the two ever disagree on padding,
        // every segment 404s, so the pattern is pinned against the path it has to produce.
        test("the ffmpeg output pattern names its files as segmentPath does, inside the run's own directory") {
            val dir = tempDir()
            val cache = SegmentCache(dir)
            val run = cache.runDir("b1", "f1", startSegment = 7, run = 1)

            val expanded = Path(cache.segmentPattern(run).replace("%05d", "00007"))

            expanded.name shouldBe cache.segmentPath("b1", "f1", 7).name
            expanded.parent?.toString() shouldBe run.toString()

            deleteRecursively(dir)
        }

        test("evicting one segment removes only that segment") {
            val dir = tempDir()
            val cache = SegmentCache(dir)
            cache.prepareDir("b1", "f1")
            for (index in 0..2) cache.segmentPath("b1", "f1", index).writeBytes(ByteArray(16))

            cache.evictSegment("b1", "f1", 1)

            cache.has("b1", "f1", 1) shouldBe false
            cache.has("b1", "f1", 0) shouldBe true
            cache.has("b1", "f1", 2) shouldBe true
            deleteRecursively(dir)
        }

        test("reports a segment absent before it is written and present after") {
            val dir = tempDir()
            val cache = SegmentCache(dir)

            cache.has("b1", "f1", 0) shouldBe false
            cache.prepareDir("b1", "f1")
            cache.segmentPath("b1", "f1", 0).writeBytes(ByteArray(16))

            cache.has("b1", "f1", 0) shouldBe true
            deleteRecursively(dir)
        }

        // ⛔ The bug this rule exists for: FFmpeg's segment muxer creates a file when it OPENS it, and a
        // run started by a seek rewrites segments an older run already finished, under the same names.
        // Neither a successor on disk nor an older run's list can vouch for the bytes being written now.
        test("a segment a run is still writing is not complete, beside its successor or named by an older run") {
            val dir = tempDir()
            val cache = SegmentCache(dir)
            val older = cache.runDir("b1", "f1", startSegment = 0, run = 1)
            val rewriting = cache.runDir("b1", "f1", startSegment = 5, run = 2)
            cache.prepareRun("b1", "f1", older)
            cache.prepareRun("b1", "f1", rewriting)
            cache.runListPath(older).writeText("seg00005.aac\n")
            Path(rewriting, "seg00005.aac").writeBytes(ByteArray(8))
            Path(rewriting, "seg00006.aac").writeBytes(ByteArray(16))

            cache.promoteFinished("b1", "f1", older)
            cache.promoteFinished("b1", "f1", rewriting)

            cache.isComplete("b1", "f1", 5) shouldBe false
            cache.isComplete("b1", "f1", 6) shouldBe false
            deleteRecursively(dir)
        }

        test("a segment is complete once its run has listed it and it is promoted into the cache") {
            val dir = tempDir()
            val cache = SegmentCache(dir)
            val run = cache.runDir("b1", "f1", startSegment = 5, run = 1)
            cache.prepareRun("b1", "f1", run)
            Path(run, "seg00005.aac").writeBytes(ByteArray(16))
            cache.runListPath(run).writeText("seg00005.aac\n")

            cache.promoteFinished("b1", "f1", run)

            cache.isComplete("b1", "f1", 5) shouldBe true
            SystemFileSystem.exists(Path(run, "seg00005.aac")) shouldBe false
            deleteRecursively(dir)
        }

        test("a run stopped mid-segment leaves nothing in the cache for the segment it was writing") {
            val dir = tempDir()
            val cache = SegmentCache(dir)
            val run = cache.runDir("b1", "f1", startSegment = 5, run = 1)
            cache.prepareRun("b1", "f1", run)
            Path(run, "seg00005.aac").writeBytes(ByteArray(16))
            Path(run, "seg00006.aac").writeBytes(ByteArray(8))
            cache.runListPath(run).writeText("seg00005.aac\n")

            cache.promoteFinished("b1", "f1", run)
            cache.discardRun(run)

            cache.has("b1", "f1", 5) shouldBe true
            cache.has("b1", "f1", 6) shouldBe false
            SystemFileSystem.exists(run) shouldBe false
            deleteRecursively(dir)
        }

        test("two runs that both finish a segment leave exactly one file, the one promoted first") {
            val dir = tempDir()
            val cache = SegmentCache(dir)
            val first = cache.runDir("b1", "f1", startSegment = 5, run = 1)
            val second = cache.runDir("b1", "f1", startSegment = 5, run = 2)
            listOf(first to 16, second to 24).forEach { (run, size) ->
                cache.prepareRun("b1", "f1", run)
                Path(run, "seg00005.aac").writeBytes(ByteArray(size))
                cache.runListPath(run).writeText("seg00005.aac\n")
            }

            cache.promoteFinished("b1", "f1", first)
            cache.promoteFinished("b1", "f1", second)

            SystemFileSystem.metadataOrNull(cache.segmentPath("b1", "f1", 5))?.size shouldBe 16L
            SystemFileSystem.exists(Path(second, "seg00005.aac")) shouldBe false
            deleteRecursively(dir)
        }

        // The layout before per-run directories wrote every run into the cache directory itself, so a
        // file there may be one a run was killed in the middle of. None of it can be trusted.
        test("a file directory left by the old shared-directory layout is cleared on first use") {
            val dir = tempDir()
            val cache = SegmentCache(dir)
            cache.prepareDir("b1", "f1")
            cache.segmentPath("b1", "f1", 5).writeBytes(ByteArray(8))
            cache.segmentPath("b1", "f1", 6).writeBytes(ByteArray(16))
            Path(cache.fileDir("b1", "f1"), "runs/from00000.list").writeText("seg00005.aac\n")

            cache.isComplete("b1", "f1", 5) shouldBe false
            cache.has("b1", "f1", 6) shouldBe false
            deleteRecursively(dir)
        }

        test("measures its own size so the sweep knows when it is over cap") {
            val dir = tempDir()
            val cache = SegmentCache(dir)
            cache.prepareDir("b1", "f1")
            cache.segmentPath("b1", "f1", 0).writeBytes(ByteArray(2048))

            cache.totalBytes() shouldBeGreaterThan 2000L
            deleteRecursively(dir)
        }

        test("evicting a file removes every segment it had") {
            val dir = tempDir()
            val cache = SegmentCache(dir)
            cache.prepareDir("b1", "f1")
            cache.segmentPath("b1", "f1", 0).writeBytes(ByteArray(16))

            cache.evict("b1", "f1")

            cache.has("b1", "f1", 0) shouldBe false
            deleteRecursively(dir)
        }
    })
