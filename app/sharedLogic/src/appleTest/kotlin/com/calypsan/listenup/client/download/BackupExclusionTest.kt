@file:OptIn(ExperimentalForeignApi::class)

package com.calypsan.listenup.client.download

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSURL
import platform.Foundation.NSURLIsExcludedFromBackupKey
import platform.Foundation.NSUUID

/**
 * Downloaded audio is server-recoverable, so it stays out of the user's iCloud quota. The flag is read
 * back through a fresh [NSURL] each time: an `NSURL` caches resource values, so reading through the
 * instance that set the value would prove nothing.
 */
class BackupExclusionTest :
    FunSpec({

        fun freshDirectory(create: Boolean = true): String {
            val path = NSTemporaryDirectory().trimEnd('/') + "/backup-exclusion-${NSUUID().UUIDString}"
            if (create) {
                NSFileManager.defaultManager.createDirectoryAtPath(
                    path,
                    withIntermediateDirectories = true,
                    attributes = null,
                    error = null,
                )
            }
            return path
        }

        fun remove(path: String) {
            NSFileManager.defaultManager.removeItemAtPath(path, error = null)
        }

        fun isExcludedFromBackup(path: String): Boolean =
            NSURL
                .fileURLWithPath(path)
                .resourceValuesForKeys(listOf(NSURLIsExcludedFromBackupKey), error = null)
                ?.get(NSURLIsExcludedFromBackupKey) as? Boolean ?: false

        test("a directory marked as excluded reports the flag") {
            val path = freshDirectory()
            try {
                excludeFromBackup(path) shouldBe true
                isExcludedFromBackup(path) shouldBe true
            } finally {
                remove(path)
            }
        }

        test("a directory that was never marked does not report the flag") {
            val path = freshDirectory()
            try {
                isExcludedFromBackup(path) shouldBe false
            } finally {
                remove(path)
            }
        }

        test("marking a path that does not exist reports failure") {
            excludeFromBackup(freshDirectory(create = false)) shouldBe false
        }
    })
