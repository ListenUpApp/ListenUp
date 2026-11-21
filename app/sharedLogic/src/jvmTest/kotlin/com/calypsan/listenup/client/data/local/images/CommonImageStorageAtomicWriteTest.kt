package com.calypsan.listenup.client.data.local.images

import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.core.BookId
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory
import kotlinx.io.readByteArray
import kotlin.uuid.Uuid

/**
 * A cover is replaced whole or not at all: an image loader reading it while a new one is saved
 * must see the old cover or the new one, never a half-written file.
 */
class CommonImageStorageAtomicWriteTest :
    FunSpec({

        fun freshFilesDir(): Path {
            val dir = Path(SystemTemporaryDirectory.toString(), "atomic-cover-test-${Uuid.random()}")
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

        fun readFile(path: Path): ByteArray = SystemFileSystem.source(path).buffered().use { it.readByteArray() }

        val book = BookId("book-1")
        val oldCover = ByteArray(64 * 1024) { 1 }
        val newCover = ByteArray(64 * 1024) { 2 }

        test("a reader that opened the old cover still reads all of it after a new one is saved") {
            val filesDir = freshFilesDir()
            val storage = storageOver(filesDir)
            storage.saveCover(book, oldCover)
            val coverFile = Path(filesDir.toString(), "covers", "book-1.jpg")

            val seen =
                SystemFileSystem.source(coverFile).buffered().use { reader ->
                    storage.saveCover(book, newCover)
                    reader.readByteArray()
                }

            seen.toList() shouldBe oldCover.toList()
            readFile(coverFile).toList() shouldBe newCover.toList()
        }

        // Two rows asking for the same uncached cover can save it at once. With one shared temp name
        // the second truncated the file the first was about to publish, or unlinked it under it.
        test("two saves of one cover at once each succeed and leave exactly one whole payload") {
            val filesDir = freshFilesDir()
            val storage = storageOver(filesDir)
            val coverFile = Path(filesDir.toString(), "covers", "book-1.jpg")
            val first = ByteArray(256 * 1024) { 3 }
            val second = ByteArray(256 * 1024) { 4 }

            repeat(200) {
                val results =
                    coroutineScope {
                        listOf(first, second)
                            .map { payload -> async(Dispatchers.Default) { storage.saveCover(book, payload) } }
                            .awaitAll()
                    }

                results.forEach { result -> result.shouldBeInstanceOf<AppResult.Success<Unit>>() }
                val onDisk = readFile(coverFile)
                (onDisk.contentEquals(first) || onDisk.contentEquals(second)) shouldBe true
            }
            SystemFileSystem.list(Path(filesDir.toString(), "covers")).map { it.name } shouldContainExactly
                listOf("book-1.jpg")
        }

        test("saving a cover leaves nothing but the cover behind") {
            val filesDir = freshFilesDir()
            val storage = storageOver(filesDir)

            storage.saveCover(book, oldCover)
            storage.saveCover(book, newCover)

            SystemFileSystem.list(Path(filesDir.toString(), "covers")).map { it.name } shouldContainExactly
                listOf("book-1.jpg")
        }
    })
