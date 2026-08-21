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

        test("DeleteFailed has stable code and is retryable") {
            val err: AppError = DownloadError.DeleteFailed()
            err.message.isNotBlank() shouldBe true
            err.code shouldBe "DOWNLOAD_DELETE_FAILED"
            err.isRetryable shouldBe true
        }

        test("DeleteFailed carries the bookTitle payload") {
            val err = DownloadError.DeleteFailed(bookTitle = "Dune")
            err.bookTitle shouldBe "Dune"
            err.message.isNotBlank() shouldBe true
        }

        test("NotSupported has stable code and is NOT retryable") {
            // Unlike DownloadFailed: a platform with no download backend (browser, desktop) will
            // never succeed no matter how many times retry middleware re-fires the call.
            val err: AppError = DownloadError.NotSupported()
            err.message.isNotBlank() shouldBe true
            err.code shouldBe "DOWNLOAD_NOT_SUPPORTED"
            err.isRetryable shouldBe false
        }
    })
