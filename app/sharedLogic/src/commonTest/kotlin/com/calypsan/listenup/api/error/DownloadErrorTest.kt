package com.calypsan.listenup.api.error

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class DownloadErrorTest :
    FunSpec({
        test("DownloadFailed has stable code and is auto-retryable") {
            val err: AppError = DownloadError.DownloadFailed()
            err.message.isNotBlank() shouldBe true
            err.code shouldBe "DOWNLOAD_FAILED"
            err.isRetryable shouldBe true
        }

        test("DownloadFailed carries the bookTitle payload") {
            val err = DownloadError.DownloadFailed(bookTitle = "Dune")
            err.bookTitle shouldBe "Dune"
            err.message.isNotBlank() shouldBe true
        }

        test("InsufficientStorage has stable code and is not auto-retryable") {
            val err: AppError = DownloadError.InsufficientStorage()
            err.message.isNotBlank() shouldBe true
            err.code shouldBe "DOWNLOAD_INSUFFICIENT_STORAGE"
            err.isRetryable shouldBe false
        }

        test("InsufficientStorage carries the bookTitle payload") {
            val err = DownloadError.InsufficientStorage(bookTitle = "Foundation")
            err.bookTitle shouldBe "Foundation"
            err.message.isNotBlank() shouldBe true
        }
    })
