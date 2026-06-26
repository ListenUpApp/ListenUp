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
    })
