@file:OptIn(ExperimentalForeignApi::class)

package com.calypsan.listenup.client.download

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSData
import platform.Foundation.NSFileManager
import platform.Foundation.NSString
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSURL
import platform.Foundation.NSURLIsExcludedFromBackupKey
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.NSUUID
import platform.Foundation.create
import platform.Foundation.dataUsingEncoding

/**
 * Resume data on disk: what lets a download stopped by a swipe-away, a Wi-Fi-only toggle or the
 * system pick up from the byte it reached instead of from zero.
 */
class ResumeDataStoreTest :
    FunSpec({
        fun freshDirectory(): String = "${NSTemporaryDirectory().trimEnd('/')}/resume-${NSUUID().UUIDString}"

        fun bytes(text: String): NSData = NSString.create(string = text).dataUsingEncoding(NSUTF8StringEncoding)!!

        test("saved resume data is read back with the expiry of the URL it resumes") {
            val store = ResumeDataStore(freshDirectory())

            store.save("file-1", bytes("partial"), urlExpiresAt = 1_800_000_000L) shouldBe true

            val blob = store.load("file-1").shouldNotBeNull()
            blob.data shouldBe bytes("partial")
            blob.urlExpiresAt shouldBe 1_800_000_000L
            store.storedFiles() shouldBe mapOf("file-1" to 1_800_000_000L)
        }

        test("saving again replaces the earlier resume data") {
            val store = ResumeDataStore(freshDirectory())
            store.save("file-1", bytes("first"), urlExpiresAt = 1L)

            store.save("file-1", bytes("second"), urlExpiresAt = null)

            val blob = store.load("file-1").shouldNotBeNull()
            blob.data shouldBe bytes("second")
            blob.urlExpiresAt.shouldBeNull()
            store.storedFiles().keys shouldBe setOf("file-1")
        }

        test("deleting forgets one file, and deleting everything forgets them all") {
            val store = ResumeDataStore(freshDirectory())
            store.save("file-1", bytes("a"), urlExpiresAt = null)
            store.save("file-2", bytes("b"), urlExpiresAt = null)

            store.delete("file-1")
            store.load("file-1").shouldBeNull()
            store.storedFiles().keys shouldBe setOf("file-2")

            store.deleteAll()
            store.storedFiles() shouldBe emptyMap()
        }

        test("resume data is kept out of backups: it is useless on any other device") {
            val directory = freshDirectory()
            ResumeDataStore(directory).save("file-1", bytes("a"), urlExpiresAt = null)

            val excluded =
                NSURL.fileURLWithPath(directory).resourceValuesForKeys(listOf(NSURLIsExcludedFromBackupKey), error = null)
            excluded?.get(NSURLIsExcludedFromBackupKey) as? Boolean shouldBe true
            NSFileManager.defaultManager.removeItemAtPath(directory, error = null)
        }

        test("an expiry is read from the signed URL a transfer started with") {
            signedUrlExpiry("https://h/api/v1/audio/b/f?u=u1&exp=1800000000&sig=ab") shouldBe 1_800_000_000L
            signedUrlExpiry("https://h/api/v1/audio/b/f?u=u1&sig=ab").shouldBeNull()
            signedUrlExpiry(null).shouldBeNull()
        }
    })
