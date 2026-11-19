package com.calypsan.listenup.client.data.local.images

import com.calypsan.listenup.core.BookId
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
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

        test("saving a cover leaves nothing but the cover behind") {
            val filesDir = freshFilesDir()
            val storage = storageOver(filesDir)

            storage.saveCover(book, oldCover)
            storage.saveCover(book, newCover)

            SystemFileSystem.list(Path(filesDir.toString(), "covers")).map { it.name } shouldContainExactly
                listOf("book-1.jpg")
        }
    })
