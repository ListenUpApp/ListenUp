package com.calypsan.listenup.client.data.local.images

import com.calypsan.listenup.core.BookId
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Tests for [CommonImageStorage.listCoverBookIds] — the single directory listing the startup
 * cover-presence reconciler uses instead of a per-book filesystem stat.
 */
@OptIn(ExperimentalUuidApi::class)
class CommonImageStorageListCoversTest :
    FunSpec({

        fun freshFilesDir(): Path {
            val dir = Path(SystemTemporaryDirectory.toString(), "listcovers-test-${Uuid.random()}")
            SystemFileSystem.createDirectories(dir)
            return dir
        }

        fun storageOver(filesDir: Path): CommonImageStorage =
            CommonImageStorage(
                storagePaths =
                    object : StoragePaths {
                        override val filesDir: Path = filesDir
                    },
            )

        test("returns an empty set when the covers directory does not exist") {
            val storage = storageOver(freshFilesDir())

            storage.listCoverBookIds() shouldBe emptySet()
        }

        test("returns the book ids of every cover file on disk") {
            val filesDir = freshFilesDir()
            val coversDir = Path(filesDir.toString(), "covers")
            SystemFileSystem.createDirectories(coversDir)
            SystemFileSystem.sink(Path(coversDir.toString(), "a.jpg")).close()
            SystemFileSystem.sink(Path(coversDir.toString(), "b.jpg")).close()
            val storage = storageOver(filesDir)

            storage.listCoverBookIds() shouldBe setOf(BookId("a"), BookId("b"))
        }

        test("excludes staging cover files") {
            val filesDir = freshFilesDir()
            val coversDir = Path(filesDir.toString(), "covers")
            SystemFileSystem.createDirectories(coversDir)
            SystemFileSystem.sink(Path(coversDir.toString(), "a.jpg")).close()
            SystemFileSystem.sink(Path(coversDir.toString(), "c_staging.jpg")).close()
            val storage = storageOver(filesDir)

            storage.listCoverBookIds() shouldBe setOf(BookId("a"))
        }

        test("excludes the series covers subdirectory") {
            val filesDir = freshFilesDir()
            val coversDir = Path(filesDir.toString(), "covers")
            val seriesDir = Path(coversDir.toString(), "series")
            SystemFileSystem.createDirectories(seriesDir)
            SystemFileSystem.sink(Path(coversDir.toString(), "a.jpg")).close()
            SystemFileSystem.sink(Path(seriesDir.toString(), "s1.jpg")).close()
            val storage = storageOver(filesDir)

            storage.listCoverBookIds() shouldBe setOf(BookId("a"))
        }

        test("excludes non-cover file extensions") {
            val filesDir = freshFilesDir()
            val coversDir = Path(filesDir.toString(), "covers")
            SystemFileSystem.createDirectories(coversDir)
            SystemFileSystem.sink(Path(coversDir.toString(), "a.jpg")).close()
            SystemFileSystem.sink(Path(coversDir.toString(), "readme.txt")).close()
            val storage = storageOver(filesDir)

            storage.listCoverBookIds() shouldBe setOf(BookId("a"))
        }
    })
