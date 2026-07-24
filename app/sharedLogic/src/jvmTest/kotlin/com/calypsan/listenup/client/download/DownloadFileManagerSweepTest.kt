package com.calypsan.listenup.client.download

import com.calypsan.listenup.client.data.local.images.StoragePaths
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import java.io.File

/**
 * Verifies that [DownloadFileManager.sweepOrphanedTempFiles] deletes `.tmp` partials whose
 * audioFileId is not in [activeAudioFileIds] and spares those that are active.
 *
 * Runs on JVM via :shared:jvmTest against the real jvmMain actual backed by a temp directory.
 */
class DownloadFileManagerSweepTest :
    FunSpec({

        fun tempDir(): File =
            File(System.getProperty("java.io.tmpdir"), "dfm-sweep-${System.nanoTime()}")
                .apply { mkdirs() }

        fun fileManagerFor(tmpRoot: File): DownloadFileManager =
            DownloadFileManager(
                storagePaths =
                    object : StoragePaths {
                        override val filesDir: Path = Path(tmpRoot.absolutePath)
                    },
            )

        test("sweepOrphanedTempFiles deletes orphaned .tmp and keeps active .tmp, returns count") {
            val tmpRoot = tempDir()
            try {
                val fileManager = fileManagerFor(tmpRoot)

                // Create .tmp files for two audioFileIds in two separate book directories.
                val activePath = fileManager.getAudioFilePath("book-1", "active-id", "track.mp3", isTemp = true)
                val orphanPath = fileManager.getAudioFilePath("book-2", "orphan-id", "track.mp3", isTemp = true)

                // Write non-empty content so they exist on disk.
                SystemFileSystem.sink(activePath).use { it }
                SystemFileSystem.sink(orphanPath).use { it }

                SystemFileSystem.exists(activePath) shouldBe true
                SystemFileSystem.exists(orphanPath) shouldBe true

                // Sweep: only "active-id" is active; "orphan-id" should be deleted.
                val deleted = fileManager.sweepOrphanedTempFiles(setOf("active-id"))

                deleted shouldBe 1
                SystemFileSystem.exists(activePath) shouldBe true
                SystemFileSystem.exists(orphanPath) shouldBe false
            } finally {
                tmpRoot.deleteRecursively()
            }
        }

        test("sweepOrphanedTempFiles spares an active partial whose audioFileId contains '_' (B10e)") {
            val tmpRoot = tempDir()
            try {
                val fileManager = fileManagerFor(tmpRoot)

                // Active download whose id contains '_'. Pre-fix, substringBefore('_') == "af" — NOT in
                // the active set {"af_1"} — so this ACTIVE partial was wrongly swept mid-download.
                val activePath = fileManager.getAudioFilePath("book-1", "af_1", "01.mp3", isTemp = true)
                val orphanPath = fileManager.getAudioFilePath("book-1", "af_2", "02.mp3", isTemp = true)
                SystemFileSystem.sink(activePath).use { it }
                SystemFileSystem.sink(orphanPath).use { it }

                val deleted = fileManager.sweepOrphanedTempFiles(setOf("af_1"))

                deleted shouldBe 1
                SystemFileSystem.exists(activePath) shouldBe true // spared — Range-resume can continue
                SystemFileSystem.exists(orphanPath) shouldBe false // genuine orphan swept
            } finally {
                tmpRoot.deleteRecursively()
            }
        }

        test("sweepOrphanedTempFiles returns 0 when no .tmp files exist") {
            val tmpRoot = tempDir()
            try {
                val fileManager = fileManagerFor(tmpRoot)
                val deleted = fileManager.sweepOrphanedTempFiles(emptySet())
                deleted shouldBe 0
            } finally {
                tmpRoot.deleteRecursively()
            }
        }

        test("sweepOrphanedTempFiles does not delete non-tmp files") {
            val tmpRoot = tempDir()
            try {
                val fileManager = fileManagerFor(tmpRoot)

                // A completed (non-tmp) file — should never be touched.
                val finalPath = fileManager.getAudioFilePath("book-1", "file-id", "track.mp3", isTemp = false)
                SystemFileSystem.sink(finalPath).use { it }

                val deleted = fileManager.sweepOrphanedTempFiles(emptySet())

                deleted shouldBe 0
                SystemFileSystem.exists(finalPath) shouldBe true
            } finally {
                tmpRoot.deleteRecursively()
            }
        }
    })
