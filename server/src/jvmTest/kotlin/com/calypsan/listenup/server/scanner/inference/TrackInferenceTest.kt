package com.calypsan.listenup.server.scanner.inference

import com.calypsan.listenup.api.dto.scanner.TrackNumberSource
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class TrackInferenceTest :
    FunSpec({

        test("track number from leading digits") {
            TrackInference.infer("01 - Chapter One.mp3", null) shouldBe
                TrackInfo(trackNumber = 1, trackSource = TrackNumberSource.FILENAME)
        }

        test("track number with 'track' prefix") {
            TrackInference.infer("track01.mp3", null) shouldBe
                TrackInfo(trackNumber = 1, trackSource = TrackNumberSource.FILENAME)
        }

        test("disc from filename with 'CD' prefix") {
            TrackInference.infer("CD2 track01.mp3", null) shouldBe
                TrackInfo(
                    trackNumber = 1,
                    discNumber = 2,
                    trackSource = TrackNumberSource.FILENAME,
                    discSource = TrackNumberSource.FILENAME,
                )
        }

        test("disc from filename with 'Disc' prefix — no track digits remain") {
            // After stripping "Disc 1", " - Chapter Two" has no digits, so trackNumber stays null.
            TrackInference.infer("Disc 1 - Chapter Two.mp3", null) shouldBe
                TrackInfo(
                    discNumber = 1,
                    discSource = TrackNumberSource.FILENAME,
                )
        }

        test("disc + track from filename when both present") {
            TrackInference.infer("Disc 2 - 05 Chapter Five.mp3", null) shouldBe
                TrackInfo(
                    trackNumber = 5,
                    discNumber = 2,
                    trackSource = TrackNumberSource.FILENAME,
                    discSource = TrackNumberSource.FILENAME,
                )
        }

        test("disc from parent folder when filename lacks one") {
            TrackInference.infer("01.mp3", "CD3") shouldBe
                TrackInfo(
                    trackNumber = 1,
                    discNumber = 3,
                    trackSource = TrackNumberSource.FILENAME,
                    discSource = TrackNumberSource.FOLDER,
                )
        }

        test("disc folder lowercase") {
            TrackInference.infer("track42.mp3", "disc4") shouldBe
                TrackInfo(
                    trackNumber = 42,
                    discNumber = 4,
                    trackSource = TrackNumberSource.FILENAME,
                    discSource = TrackNumberSource.FOLDER,
                )
        }

        test("disc folder with disk variant") {
            TrackInference.infer("01.mp3", "Disk 5") shouldBe
                TrackInfo(
                    trackNumber = 1,
                    discNumber = 5,
                    trackSource = TrackNumberSource.FILENAME,
                    discSource = TrackNumberSource.FOLDER,
                )
        }

        test("filename disc beats folder disc") {
            TrackInference.infer("CD9 track01.mp3", "CD2") shouldBe
                TrackInfo(
                    trackNumber = 1,
                    discNumber = 9,
                    trackSource = TrackNumberSource.FILENAME,
                    discSource = TrackNumberSource.FILENAME,
                )
        }

        test("no track or disc — both null") {
            TrackInference.infer("audiobook.m4b", null) shouldBe TrackInfo()
        }

        test("4-digit track number caps at the regex limit") {
            TrackInference.infer("9999 - End.mp3", null) shouldBe
                TrackInfo(trackNumber = 9999, trackSource = TrackNumberSource.FILENAME)
        }

        test("ignores 5-digit numbers (a long numeric blob is not a track number)") {
            // A complete 5-digit run is not a track — it is skipped entirely,
            // not truncated to a 4-digit prefix.
            TrackInference.infer("12345.mp3", null) shouldBe TrackInfo()
        }

        test("prefers the last digit run over a leading year-like token") {
            // "1984 - 12.mp3": leading 1984 is the book's title/year, 12 is the track.
            TrackInference.infer("1984 - 12.mp3", null) shouldBe
                TrackInfo(trackNumber = 12, trackSource = TrackNumberSource.FILENAME)
        }

        test("single leading NN prefix still infers as the track") {
            // When "NN - Title" is the only digit run, last == first.
            TrackInference.infer("1984 - 1.mp3", null) shouldBe
                TrackInfo(trackNumber = 1, trackSource = TrackNumberSource.FILENAME)
        }

        test("word-number filenames yield no track (handled by natural sort tiebreak)") {
            TrackInference.infer("Part One.mp3", null) shouldBe TrackInfo()
            TrackInference.infer("Part Ten.mp3", null) shouldBe TrackInfo()
        }
    })
