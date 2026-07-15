package com.calypsan.listenup.server.io

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.io.files.Path

class FileHelpersTest :
    FunSpec({
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

        test("relativeTo returns empty string when path equals base") {
            Path("/mnt/lib").relativeTo(Path("/mnt/lib")) shouldBe ""
        }
        test("relativeTo treats a trailing slash on base as equal") {
            Path("/mnt/lib").relativeTo(Path("/mnt/lib/")) shouldBe ""
        }
        test("relativeTo strips the base prefix for a direct child") {
            Path("/mnt/lib/Author").relativeTo(Path("/mnt/lib")) shouldBe "Author"
        }
        test("relativeTo strips the base prefix for a deep child") {
            Path("/mnt/lib/Author/Series/Book").relativeTo(Path("/mnt/lib")) shouldBe "Author/Series/Book"
        }
        test("relativeTo preserves special characters in the remainder") {
            Path("/mnt/lib/John O'Donohue/Anam Cara").relativeTo(Path("/mnt/lib")) shouldBe "John O'Donohue/Anam Cara"
        }
        test("relativeTo returns null for a sibling that merely shares a name prefix") {
            Path("/mnt/library2/Book").relativeTo(Path("/mnt/lib")) shouldBe null
        }
        test("relativeTo returns null for an unrelated path") {
            Path("/other/Book").relativeTo(Path("/mnt/lib")) shouldBe null
        }
    })
