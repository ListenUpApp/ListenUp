package com.calypsan.listenup.server.transcode

import com.calypsan.listenup.server.io.deleteRecursively
import com.calypsan.listenup.server.io.writeBytes
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
        test("the ffmpeg output pattern expands to the same names segmentPath expects") {
            val dir = tempDir()
            val cache = SegmentCache(dir)

            val expanded = cache.segmentPattern("b1", "f1").replace("%05d", "00007")

            expanded shouldBe cache.segmentPath("b1", "f1", 7).toString()

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
