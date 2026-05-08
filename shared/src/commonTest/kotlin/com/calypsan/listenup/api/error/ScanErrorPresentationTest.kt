package com.calypsan.listenup.api.error

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class ScanErrorPresentationTest :
    FunSpec({
        test("AlreadyRunning has stable code and is not auto-retryable") {
            val err = ScanError.AlreadyRunning()
            err.code shouldBe "SCAN_ALREADY_RUNNING"
            err.isRetryable shouldBe false // user must wait for current scan; not "same call may succeed"
        }

        test("LibraryPathNotConfigured is not auto-retryable without config change") {
            val err = ScanError.LibraryPathNotConfigured()
            err.code shouldBe "SCAN_LIBRARY_PATH_NOT_CONFIGURED"
            err.isRetryable shouldBe false
        }

        test("FileUnreadable carries debug detail and stable user-facing message") {
            val err = ScanError.FileUnreadable(path = "/lib/broken.mp3", debugInfo = "permission denied")
            err.code shouldBe "SCAN_FILE_UNREADABLE"
            err.isRetryable shouldBe false
            err.message shouldBe "A file in the library could not be read."
            err.message.isNotBlank() shouldBe true
            err.debugInfo shouldBe "permission denied"
        }

        test("MetadataParseError carries debug detail and stable user-facing message") {
            val err = ScanError.MetadataParseError(path = "/lib/book/metadata.json", debugInfo = "bad json")
            err.code shouldBe "SCAN_METADATA_PARSE_ERROR"
            err.isRetryable shouldBe false
            err.message shouldBe "Could not read metadata for this file."
            err.message.isNotBlank() shouldBe true
            err.debugInfo shouldBe "bad json"
        }
    })
