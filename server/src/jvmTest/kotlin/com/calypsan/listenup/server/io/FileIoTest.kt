package com.calypsan.listenup.server.io

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.comparables.shouldBeGreaterThan
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldEndWith
import io.kotest.matchers.string.shouldStartWith
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeText

/** Bridges a java.nio temp dir/file into a kotlinx.io [Path]. */
private fun java.nio.file.Path.toKxio(): Path = Path(this.toString())

class FileIoTest :
    FunSpec({
        test("statFile returns the written file's size") {
            val dir = createTempDirectory("file-io-stat")
            val file = dir.resolve("payload.bin").apply { writeText("hello world") }

            val attrs = statFile(file.toKxio())

            attrs.shouldNotBeNull()
            attrs.size shouldBe "hello world".length.toLong()
        }

        test("statFile reports a plausible recent mtime in epoch-ms") {
            val dir = createTempDirectory("file-io-mtime")
            val file = dir.resolve("recent.txt").apply { writeText("now") }

            val attrs = statFile(file.toKxio())

            attrs.shouldNotBeNull()
            // Sanity bound: any file written today is well past 2023-11-14 in epoch-ms.
            attrs.mtimeMs shouldBeGreaterThan 1_700_000_000_000L
        }

        test("statFile exposes a non-null inode on Linux") {
            val dir = createTempDirectory("file-io-inode")
            val file = dir.resolve("ino.txt").apply { writeText("x") }

            val attrs = statFile(file.toKxio())

            attrs.shouldNotBeNull()
            attrs.inode.shouldNotBeNull()
        }

        test("statFile returns null for a nonexistent path") {
            val dir = createTempDirectory("file-io-missing")
            val missing = dir.resolve("does-not-exist.txt")

            statFile(missing.toKxio()).shouldBeNull()
        }

        test("canonicalize collapses a `..` round-trip back to the real path") {
            val dir = createTempDirectory("file-io-canon")
            val nested = dir.resolve("a").resolve("b")
            java.nio.file.Files
                .createDirectories(nested)
            val real = nested.resolve("file.txt").apply { writeText("c") }

            // a/../a/b/file.txt — the `..` must collapse to the same canonical path.
            val winding =
                dir
                    .resolve("a")
                    .resolve("..")
                    .resolve("a")
                    .resolve("b")
                    .resolve("file.txt")

            canonicalize(winding.toKxio()).toString() shouldBe canonicalize(real.toKxio()).toString()
        }

        test("deleteRecursively removes a nested directory tree") {
            val root = createTempDirectory("file-io-rmtree")
            val deep = root.resolve("x").resolve("y")
            java.nio.file.Files
                .createDirectories(deep)
            deep.resolve("leaf.txt").writeText("bye")
            root.resolve("top.txt").writeText("bye")

            val rootKxio = root.toKxio()
            SystemFileSystem.exists(rootKxio).shouldBeTrue()

            deleteRecursively(rootKxio)

            SystemFileSystem.exists(rootKxio).shouldBeFalse()
        }

        test("deleteRecursively is a no-op for an absent path") {
            val dir = createTempDirectory("file-io-rm-absent")
            val absent = dir.resolve("gone").toKxio()

            deleteRecursively(absent) // must not throw

            SystemFileSystem.exists(absent).shouldBeFalse()
        }

        test("createTempFileIn creates a prefixed/suffixed file inside the dir") {
            val dir = createTempDirectory("file-io-temp").toKxio()

            val created = createTempFileIn(dir, prefix = "lu-", suffix = ".tmp")

            SystemFileSystem.exists(created).shouldBeTrue()
            created.name.shouldStartWith("lu-")
            created.name.shouldEndWith(".tmp")
        }

        test("readTextCapped returns the content of a file exactly at the cap") {
            val dir = createTempDirectory("file-io-cap-text-at")
            val file = dir.resolve("at-cap.txt").apply { writeText("x".repeat(64)) }

            file.toKxio().readTextCapped(maxBytes = 64) shouldBe "x".repeat(64)
        }

        test("readTextCapped refuses a file one byte over the cap") {
            val dir = createTempDirectory("file-io-cap-text-over")
            val file = dir.resolve("over-cap.txt").apply { writeText("x".repeat(65)) }

            file.toKxio().readTextCapped(maxBytes = 64).shouldBeNull()
        }

        test("readTextCapped returns null for a nonexistent path rather than throwing") {
            val dir = createTempDirectory("file-io-cap-text-missing")

            dir
                .resolve("absent.txt")
                .toKxio()
                .readTextCapped(maxBytes = 64)
                .shouldBeNull()
        }

        test("readBytesCapped returns the content of a file exactly at the cap") {
            val dir = createTempDirectory("file-io-cap-bytes-at")
            val file = dir.resolve("at-cap.bin").apply { writeText("y".repeat(64)) }

            file.toKxio().readBytesCapped(maxBytes = 64)?.size shouldBe 64
        }

        test("readBytesCapped refuses a file one byte over the cap") {
            val dir = createTempDirectory("file-io-cap-bytes-over")
            val file = dir.resolve("over-cap.bin").apply { writeText("y".repeat(65)) }

            file.toKxio().readBytesCapped(maxBytes = 64).shouldBeNull()
        }

        test("createTempFileIn yields distinct paths on repeated calls") {
            val dir = createTempDirectory("file-io-temp-distinct").toKxio()

            val first = createTempFileIn(dir, prefix = "lu-", suffix = ".tmp")
            val second = createTempFileIn(dir, prefix = "lu-", suffix = ".tmp")

            (first.toString() == second.toString()).shouldBeFalse()
        }
    })
