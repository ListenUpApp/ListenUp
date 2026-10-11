@file:OptIn(ExperimentalForeignApi::class)

package com.calypsan.listenup.client.data.local.images

import com.calypsan.listenup.client.download.excludeFromBackup
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.io.files.Path
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSURL
import platform.Foundation.NSURLIsExcludedFromBackupKey
import platform.Foundation.NSUUID

/**
 * Covers, contributor photos, series covers and avatars are all one download from the server, so
 * they stay out of the user's iCloud quota. Each directory is checked on its own: the flag belongs to
 * the directory entry, and a fresh [NSURL] reads it back so a cached value cannot prove anything.
 */
class ImageBackupExclusionTest :
    FunSpec({

        val imageDirectories = listOf("covers", "covers/series", "contributors", "avatars")

        fun freshRoot(): String = NSTemporaryDirectory().trimEnd('/') + "/image-backup-${NSUUID().UUIDString}"

        fun remove(path: String) {
            NSFileManager.defaultManager.removeItemAtPath(path, error = null)
        }

        fun isExcludedFromBackup(path: String): Boolean =
            NSURL
                .fileURLWithPath(path)
                .resourceValuesForKeys(listOf(NSURLIsExcludedFromBackupKey), error = null)
                ?.get(NSURLIsExcludedFromBackupKey) as? Boolean ?: false

        fun storageAt(root: String) =
            CommonImageStorage(
                storagePaths =
                    object : StoragePaths {
                        override val filesDir: Path = Path(root)
                    },
                markServerRecoverable = { directory -> excludeFromBackup(directory.toString()) },
            )

        imageDirectories.forEach { directory ->
            test("$directory is excluded from backup once the storage is initialised") {
                val root = freshRoot()
                try {
                    storageAt(root)

                    isExcludedFromBackup("$root/$directory") shouldBe true
                } finally {
                    remove(root)
                }
            }

            test("$directory is excluded again after it is removed and written to through the storage") {
                val root = freshRoot()
                try {
                    val storage = storageAt(root)
                    remove("$root/covers")
                    remove("$root/contributors")
                    remove("$root/avatars")

                    storage.saveCover(
                        com.calypsan.listenup.core
                            .BookId("book-1"),
                        byteArrayOf(1),
                    )
                    storage.saveSeriesCover("series-1", byteArrayOf(1))
                    storage.saveContributorImage("contributor-1", byteArrayOf(1))
                    storage.saveUserAvatar("user-1", byteArrayOf(1))

                    isExcludedFromBackup("$root/$directory") shouldBe true
                } finally {
                    remove(root)
                }
            }
        }
    })
