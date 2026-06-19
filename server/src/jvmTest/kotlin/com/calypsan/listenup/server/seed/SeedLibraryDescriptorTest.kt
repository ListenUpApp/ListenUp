package com.calypsan.listenup.server.seed

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe

class SeedLibraryDescriptorTest :
    FunSpec({
        val books = SeedLibraryDescriptor.BOOKS

        test("the library has 10-12 books") {
            (books.size in 10..12) shouldBe true
        }

        test("every book has a unique folder path and at least one audio track") {
            books.map { it.folderPath }.toSet().size shouldBe books.size
            books.forEach { book -> book.tracks.size shouldBeGreaterThanOrEqual 1 }
        }

        test("the library covers both single-file and multi-file audiobooks") {
            books.any { it.tracks.size == 1 } shouldBe true
            books.any { it.tracks.size > 1 } shouldBe true
        }

        test("the library includes the required edge cases") {
            books.any { !it.hasCover } shouldBe true
            books.any { it.title.length > 80 } shouldBe true
            books.any { it.narrators.size > 1 } shouldBe true
            books.map { it.sidecar }.toSet() shouldContain SeedSidecar.NONE
        }

        test("every sidecar kind is exercised at least once") {
            val used = books.map { it.sidecar }.toSet()
            SeedSidecar.entries.forEach { kind -> used shouldContain kind }
        }

        test("at least one book belongs to a multi-book series") {
            val seriesCounts = books.mapNotNull { it.series?.name }.groupingBy { it }.eachCount()
            seriesCounts.values.any { it >= 2 } shouldBe true
        }
    })
