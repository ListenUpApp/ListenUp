package com.calypsan.listenup.api.error

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class ScanErrorPresentationTest : FunSpec({
    test("AlreadyRunning has stable code and is not auto-retryable") {
        val err = ScanError.AlreadyRunning()
        err.code shouldBe "SCAN_ALREADY_RUNNING"
        err.isRetryable shouldBe false   // user must wait for current scan; not "same call may succeed"
    }

    test("LibraryPathNotConfigured is not retryable without config change") {
        val err = ScanError.LibraryPathNotConfigured()
        err.code shouldBe "SCAN_LIBRARY_PATH_NOT_CONFIGURED"
        err.isRetryable shouldBe false
    }

    test("FileUnreadable carries per-instance message") {
        val err = ScanError.FileUnreadable(path = "/lib/broken.mp3", message = "permission denied")
        err.code shouldBe "SCAN_FILE_UNREADABLE"
        err.message.isNotBlank() shouldBe true
    }
})
