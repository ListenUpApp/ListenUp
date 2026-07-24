package com.calypsan.listenup.api.error

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class ImportErrorTest :
    FunSpec({
        test("UploadFailed has stable code and is auto-retryable") {
            val err: AppError = ImportError.UploadFailed()
            err.message.isNotBlank() shouldBe true
            err.code shouldBe "IMPORT_UPLOAD_FAILED"
            err.isRetryable shouldBe true
        }

        test("AnalysisFailed has stable code and is auto-retryable") {
            val err: AppError = ImportError.AnalysisFailed()
            err.message.isNotBlank() shouldBe true
            err.code shouldBe "IMPORT_ANALYSIS_FAILED"
            err.isRetryable shouldBe true
        }

        test("ApplyFailed has stable code and is auto-retryable") {
            val err: AppError = ImportError.ApplyFailed()
            err.message.isNotBlank() shouldBe true
            err.code shouldBe "IMPORT_APPLY_FAILED"
            err.isRetryable shouldBe true
        }
    })
