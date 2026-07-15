package com.calypsan.listenup.server.scanner.inference

import com.calypsan.listenup.api.dto.scanner.FileType
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class FileTypeRulesTest :
    FunSpec({

        test("classifies audio extensions as AUDIO") {
            listOf(
                "track.m4b",
                "track.mp3",
                "track.m4a",
                "track.m4p",
                "track.flac",
                "track.opus",
                "track.ogg",
                "track.oga",
                "track.mp4",
                "track.aac",
                "track.wma",
                "track.aiff",
                "track.aif",
                "track.wav",
                "track.webm",
                "track.webma",
                "track.mka",
                "track.awb",
                "track.caf",
            ).forEach { FileTypeRules.classify(it) shouldBe FileType.AUDIO }
        }

        test("does NOT classify MPEG video containers as AUDIO") {
            // mpg/mpeg are video containers ABS mis-lists as audio; a stray video clip must not
            // become a failing "book".
            FileTypeRules.classify("clip.mpg") shouldBe FileType.UNKNOWN
            FileTypeRules.classify("clip.mpeg") shouldBe FileType.UNKNOWN
        }

        test("classifies image extensions as IMAGE") {
            listOf("cover.jpg", "cover.jpeg", "cover.png", "cover.webp").forEach {
                FileTypeRules.classify(it) shouldBe FileType.IMAGE
            }
        }

        test("classifies ebook extensions as EBOOK") {
            listOf("book.epub", "book.pdf", "book.mobi", "book.azw3", "comic.cbr", "comic.cbz").forEach {
                FileTypeRules.classify(it) shouldBe FileType.EBOOK
            }
        }

        test("classifies text extensions as TEXT") {
            FileTypeRules.classify("desc.txt") shouldBe FileType.TEXT
            FileTypeRules.classify("info.nfo") shouldBe FileType.TEXT
        }

        test("classifies metadata extensions as METADATA") {
            listOf("metadata.json", "book.opf", "book.abs", "book.xml").forEach {
                FileTypeRules.classify(it) shouldBe FileType.METADATA
            }
        }

        test("uppercase extensions classify the same as lowercase") {
            FileTypeRules.classify("TRACK.MP3") shouldBe FileType.AUDIO
            FileTypeRules.classify("Cover.JPG") shouldBe FileType.IMAGE
        }

        test("unknown and missing extensions return UNKNOWN") {
            FileTypeRules.classify("README") shouldBe FileType.UNKNOWN
            FileTypeRules.classify("track.xyz") shouldBe FileType.UNKNOWN
            FileTypeRules.classify("plain") shouldBe FileType.UNKNOWN
        }
    })
