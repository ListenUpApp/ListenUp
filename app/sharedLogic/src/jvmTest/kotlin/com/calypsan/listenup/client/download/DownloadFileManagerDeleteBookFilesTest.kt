package com.calypsan.listenup.client.download

import com.calypsan.listenup.client.data.local.images.StoragePaths
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import java.io.File

/**
 * Pins the honest boolean contract of [DownloadFileManager.deleteBookFiles] (audit finding): the
 * pre-fix Android/Desktop actuals discarded `File.deleteRecursively()`'s return value, so a
 * partial delete failure was reported as success — orphaning bytes on disk with no way for the
 * caller ([com.calypsan.listenup.client.download.DownloadManager.deleteDownload]) to know.
 *
 * Runs on JVM via :shared:jvmTest against the real jvmMain actual backed by a temp directory —
 * same real-filesystem approach as [DownloadFileManagerSweepTest], and the jvmMain actual shares
 * the exact `File.deleteRecursively()` shape the Android actual uses.
 */
class DownloadFileManagerDeleteBookFilesTest :
    FunSpec({

        fun tempDir(): File =
            File(System.getProperty("java.io.tmpdir"), "dfm-delete-${System.nanoTime()}")
                .apply { mkdirs() }

        fun fileManagerFor(tmpRoot: File): DownloadFileManager =
            DownloadFileManager(
                storagePaths =
                    object : StoragePaths {
                        override val filesDir: Path = Path(tmpRoot.absolutePath)
                    },
            )

        test("deleteBookFiles returns true and removes every file on a normal delete") {
            val tmpRoot = tempDir()
            try {
                val fileManager = fileManagerFor(tmpRoot)
                val filePath = fileManager.getAudioFilePath("book-1", "af-1", "01.mp3", isTemp = false)
                SystemFileSystem.sink(filePath).use { it }
                SystemFileSystem.exists(filePath) shouldBe true

                fileManager.deleteBookFiles("book-1") shouldBe true
                SystemFileSystem.exists(filePath) shouldBe false
            } finally {
                tmpRoot.deleteRecursively()
            }
        }

        test("deleteBookFiles returns true when the book directory never existed (nothing to delete)") {
            val tmpRoot = tempDir()
            try {
                val fileManager = fileManagerFor(tmpRoot)

                fileManager.deleteBookFiles("never-downloaded") shouldBe true
            } finally {
                tmpRoot.deleteRecursively()
            }
        }

        test("deleteBookFiles returns false when a file cannot be removed") {
            val tmpRoot = tempDir()
            try {
                val fileManager = fileManagerFor(tmpRoot)
                val filePath = fileManager.getAudioFilePath("book-1", "af-1", "01.mp3", isTemp = false)
                SystemFileSystem.sink(filePath).use { it }

                // Real, portable failure: chmod the book directory read-only so the file inside it
                // can't be unlinked. Neither the local dev box nor CI runs these tests as root, so
                // this reliably fails deleteRecursively() rather than silently succeeding.
                val bookDir = File(filePath.toString()).parentFile!!
                bookDir.setWritable(false)
                try {
                    fileManager.deleteBookFiles("book-1") shouldBe false
                } finally {
                    bookDir.setWritable(true) // restore so the outer cleanup can remove it
                }
            } finally {
                tmpRoot.deleteRecursively()
            }
        }
    })
