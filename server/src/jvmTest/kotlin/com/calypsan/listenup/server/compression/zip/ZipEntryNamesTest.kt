package com.calypsan.listenup.server.compression.zip

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class ZipEntryNamesTest :
    FunSpec({
        test("isSafeEntryName accepts relative paths, rejects traversal/absolute") {
            isSafeEntryName("covers/book-1/cover.jpg") shouldBe true
            isSafeEntryName("manifest.json") shouldBe true
            isSafeEntryName("../x") shouldBe false
            isSafeEntryName("a/../../b") shouldBe false
            isSafeEntryName("/abs") shouldBe false
            isSafeEntryName("C:\\x") shouldBe false
            isSafeEntryName("a/../b") shouldBe false
            isSafeEntryName("") shouldBe false
        }

        test("isSafeEntryName rejects names containing control characters") {
            isSafeEntryName("x\u0000.txt") shouldBe false // NUL
            isSafeEntryName("x\u0001.txt") shouldBe false // SOH
            isSafeEntryName("..") shouldBe false
            isSafeEntryName("foo/") shouldBe true
        }
    })
