package com.calypsan.listenup.server.scanner.inference

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.createFile
import kotlin.io.path.div

class SkipRulesTest :
    FunSpec({

        test("skips dotfiles") {
            SkipRules.shouldSkip(Path.of("/library/.DS_Store")) shouldBe true
            SkipRules.shouldSkip(Path.of("/library/.git")) shouldBe true
        }

        test("skips temp / partial-download extensions case-insensitively") {
            SkipRules.shouldSkip(Path.of("/library/audiobook.part")) shouldBe true
            SkipRules.shouldSkip(Path.of("/library/audiobook.TMP")) shouldBe true
            SkipRules.shouldSkip(Path.of("/library/audiobook.crdownload")) shouldBe true
            SkipRules.shouldSkip(Path.of("/library/cover.bak")) shouldBe true
        }

        test("skips Synology @eaDir junk on POSIX-style paths") {
            SkipRules.shouldSkip(Path.of("/library/Author/Book/@eaDir/SYNOPHOTO_THUMB_M.jpg")) shouldBe true
        }

        test(".ignore sibling marks the directory as skipped") {
            val tmp = Files.createTempDirectory("listenup-skip-")
            try {
                val bookDir = tmp / "BookFolder"
                bookDir.createDirectories()
                (bookDir / ".ignore").createFile()
                val audio = bookDir / "track.mp3"
                audio.createFile()
                SkipRules.shouldSkip(audio) shouldBe true
            } finally {
                tmp.toFile().deleteRecursively()
            }
        }

        test("does not skip ordinary audio files") {
            SkipRules.shouldSkip(Path.of("/library/Author/Book/track.mp3")) shouldBe false
            SkipRules.shouldSkip(Path.of("/library/Author/Book/cover.jpg")) shouldBe false
            SkipRules.shouldSkip(Path.of("/library/Author/Book/metadata.json")) shouldBe false
        }
    })
