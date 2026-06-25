package com.calypsan.listenup.server.io

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.io.files.Path

class FileHelpersTest : FunSpec({
    test("isUnder is true for a direct child") {
        Path("/lib/books/book-1").isUnder(Path("/lib/books")) shouldBe true
    }
    test("isUnder is true for the path itself (equal)") {
        Path("/lib/books").isUnder(Path("/lib/books")) shouldBe true
    }
    test("isUnder ignores a trailing slash on the base") {
        Path("/lib/books/book-1").isUnder(Path("/lib/books/")) shouldBe true
    }
    test("isUnder is false for a sibling with a shared prefix") {
        Path("/lib/books-extra/x").isUnder(Path("/lib/books")) shouldBe false
    }
    test("isUnder is false for an unrelated path") {
        Path("/other/x").isUnder(Path("/lib/books")) shouldBe false
    }
})
