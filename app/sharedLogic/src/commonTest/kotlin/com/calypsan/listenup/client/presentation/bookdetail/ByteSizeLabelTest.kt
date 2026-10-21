package com.calypsan.listenup.client.presentation.bookdetail

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * One size, said the same way on Android, iOS and web: the rig drive found a download that read "2 MB" on web and
 * "2.7 MB" on Android. Binary units, one decimal where it helps, none past 100.
 */
class ByteSizeLabelTest :
    FunSpec({
        test("bytes and kilobytes are whole numbers") {
            byteSizeLabel(0L) shouldBe "0 B"
            byteSizeLabel(512L) shouldBe "512 B"
            byteSizeLabel(512L * 1024) shouldBe "512 KB"
        }

        test("megabytes keep one decimal below 100, and drop a .0") {
            byteSizeLabel(2_831_155L) shouldBe "2.7 MB"
            byteSizeLabel(18L * 1024 * 1024) shouldBe "18 MB"
            byteSizeLabel(539_000_000L) shouldBe "514 MB"
            byteSizeLabel(539_492_352L) shouldBe "515 MB"
        }

        test("gigabytes keep one decimal") {
            byteSizeLabel(1_288_490_189L) shouldBe "1.2 GB"
            byteSizeLabel(2L * 1024 * 1024 * 1024) shouldBe "2 GB"
        }

        test("a negative size reads as nothing") {
            byteSizeLabel(-5L) shouldBe "0 B"
        }
    })
