package com.calypsan.listenup.server.absimport

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * Pins [isSafeImportId] — the path-traversal guard the RPC import surface applies to a
 * client-supplied `ImportId` before any filesystem access. Mirrors the backup-id contract.
 */
class ImportPathsTest :
    FunSpec({
        test("accepts a well-formed server-minted id") {
            isSafeImportId("01H9ZABCDE1234567890") shouldBe true
            isSafeImportId("import-2026-07-13") shouldBe true
        }

        test("rejects blank ids") {
            isSafeImportId("") shouldBe false
            isSafeImportId("   ") shouldBe false
        }

        test("rejects path separators and traversal sequences") {
            isSafeImportId("../etc/passwd") shouldBe false
            isSafeImportId("..") shouldBe false
            isSafeImportId("a/b") shouldBe false
            isSafeImportId("a\\b") shouldBe false
            isSafeImportId("foo/../bar") shouldBe false
        }
    })
