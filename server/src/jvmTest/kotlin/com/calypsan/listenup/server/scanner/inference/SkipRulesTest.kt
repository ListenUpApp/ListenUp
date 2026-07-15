package com.calypsan.listenup.server.scanner.inference

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.nio.file.Files
import kotlin.io.path.createDirectories
import kotlin.io.path.createFile
import kotlin.io.path.div
import kotlinx.io.files.Path

class SkipRulesTest :
    FunSpec({

        test("skips dotfiles") {
            SkipRules.shouldSkip(Path("/library/.DS_Store")) shouldBe true
            SkipRules.shouldSkip(Path("/library/.git")) shouldBe true
        }

        test("skips temp / partial-download extensions case-insensitively") {
            SkipRules.shouldSkip(Path("/library/audiobook.part")) shouldBe true
            SkipRules.shouldSkip(Path("/library/audiobook.TMP")) shouldBe true
            SkipRules.shouldSkip(Path("/library/audiobook.crdownload")) shouldBe true
            SkipRules.shouldSkip(Path("/library/cover.bak")) shouldBe true
        }

        test("skips Synology @eaDir junk on POSIX-style paths") {
            SkipRules.shouldSkip(Path("/library/Author/Book/@eaDir/SYNOPHOTO_THUMB_M.jpg")) shouldBe true
        }

        test("skips the @eaDir directory itself so the Walker never descends into it") {
            // The substring check only matches CHILDREN of @eaDir; the name check prunes the
            // directory itself.
            SkipRules.shouldSkipByName(Path("/library/Author/Book/@eaDir")) shouldBe true
        }

        test("shouldSkipByName does not probe the filesystem for a .ignore sibling") {
            // Name-only decision — an ordinary file with no .ignore sibling is not skipped, and no
            // exists() syscall is made (the Walker owns the per-directory probe).
            SkipRules.shouldSkipByName(Path("/library/Author/Book/track.mp3")) shouldBe false
        }

        test(".ignore sibling marks the directory as skipped") {
            val tmp = Files.createTempDirectory("listenup-skip-")
            try {
                val bookDir = tmp / "BookFolder"
                bookDir.createDirectories()
                (bookDir / ".ignore").createFile()
                val audio = bookDir / "track.mp3"
                audio.createFile()
                SkipRules.shouldSkip(Path(audio.toString())) shouldBe true
            } finally {
                tmp.toFile().deleteRecursively()
            }
        }

        test("does not skip ordinary audio files") {
            SkipRules.shouldSkip(Path("/library/Author/Book/track.mp3")) shouldBe false
            SkipRules.shouldSkip(Path("/library/Author/Book/cover.jpg")) shouldBe false
            SkipRules.shouldSkip(Path("/library/Author/Book/metadata.json")) shouldBe false
        }
    })
