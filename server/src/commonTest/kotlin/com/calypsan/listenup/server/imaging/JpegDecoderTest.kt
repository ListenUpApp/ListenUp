package com.calypsan.listenup.server.imaging

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.ints.shouldBeLessThan
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe

/**
 * Scaled JPEG decoding — the piece that decides whether this arc works.
 *
 * Every fixture is the same picture — left half red, right half blue — written by PIL, an
 * independent encoder, so our own encoder cannot mask a bug here. The first two are the **same
 * 16x16 image** at 4:2:0 and quality 92, one baseline (SOF0) and one progressive (SOF2);
 * **589 of 1180 real covers are progressive**, so their equivalence is not a nicety, it is the
 * difference between fixing the library grid and fixing half of it. The third adds the detail that
 * a smooth image never carries, because smoothness is what let a whole class of bug hide here.
 *
 * Assertions sit on the outer columns rather than the middle. 4:2:0 subsampling shares chroma
 * across pairs of pixels, so the red/blue boundary is legitimately muddy; the edges are where the
 * decoder's correctness is unambiguous.
 */
class JpegDecoderTest :
    FunSpec({

        val baseline =
            hexBytes(
                "ffd8ffe000104a46494600010100000100010000ffdb00430003020202020203020202030303030406040404" +
                    "0404080606050609080a0a090809090a0c0f0c0a0b0e0b09090d110d0e0f101011100a0c12131210130f1010" +
                    "10ffdb00430103030304030408040408100b090b101010101010101010101010101010101010101010101010" +
                    "1010101010101010101010101010101010101010101010101010ffc000110800100010030122000211010311" +
                    "01ffc4001f0000010501010101010100000000000000000102030405060708090a0bffc400b5100002010303" +
                    "020403050504040000017d01020300041105122131410613516107227114328191a1082342b1c11552d1f024" +
                    "33627282090a161718191a25262728292a3435363738393a434445464748494a535455565758595a63646566" +
                    "6768696a737475767778797a838485868788898a92939495969798999aa2a3a4a5a6a7a8a9aab2b3b4b5b6b7" +
                    "b8b9bac2c3c4c5c6c7c8c9cad2d3d4d5d6d7d8d9dae1e2e3e4e5e6e7e8e9eaf1f2f3f4f5f6f7f8f9faffc400" +
                    "1f0100030101010101010101010000000000000102030405060708090a0bffc400b511000201020404030407" +
                    "05040400010277000102031104052131061241510761711322328108144291a1b1c109233352f0156272d10a" +
                    "162434e125f11718191a262728292a35363738393a434445464748494a535455565758595a63646566676869" +
                    "6a737475767778797a82838485868788898a92939495969798999aa2a3a4a5a6a7a8a9aab2b3b4b5b6b7b8b9" +
                    "bac2c3c4c5c6c7c8c9cad2d3d4d5d6d7d8d9dae2e3e4e5e6e7e8e9eaf2f3f4f5f6f7f8f9faffda000c030100" +
                    "02110311003f00f34af3faf40af3fafb0f007fe663ff00707ff729f7be3b7fccbffee2ff00ee33ffd9",
            )

        val progressive =
            hexBytes(
                "ffd8ffe000104a46494600010100000100010000ffdb00430003020202020203020202030303030406040404" +
                    "0404080606050609080a0a090809090a0c0f0c0a0b0e0b09090d110d0e0f101011100a0c12131210130f1010" +
                    "10ffdb00430103030304030408040408100b090b101010101010101010101010101010101010101010101010" +
                    "1010101010101010101010101010101010101010101010101010ffc200110800100010030122000211010311" +
                    "01ffc40014000100000000000000000000000000000006ffc400140101000000000000000000000000000000" +
                    "05ffda000c03010002100310000001327d01f61effc40014100100000000000000000000000000000020ffda" +
                    "00080101000105021fffc40018110002030000000000000000000000000000064482c2ffda0008010301013f" +
                    "017b8f6c9fffc40018110002030000000000000000000000000000064482c2ffda0008010201013f0140914d" +
                    "1fffc40014100100000000000000000000000000000020ffda0008010100063f021fffc40014100100000000" +
                    "000000000000000000000020ffda0008010100013f211fffda000c03010002000300000010f7ffc400141101" +
                    "00000000000000000000000000000000ffda0008010301013f1017ffc4001411010000000000000000000000" +
                    "0000000000ffda0008010201013f102fffc40014100100000000000000000000000000000020ffda00080101" +
                    "00013f101fffd9",
            )

        // A third image, for the coefficient runs a smooth one never produces: 8x8, 4:4:4,
        // quality 98, a red/blue split under a per-pixel checkerboard. The checkerboard is exactly
        // the highest-frequency basis function, so every block's coefficients are DC, a little
        // low-frequency detail, then a long run of zeroes to reach coefficient 63 — which is coded
        // as ZRL and is the case a decoder is most likely to get wrong.
        val zeroRun =
            hexBytes(
                "ffd8ffe000104a46494600010100000100010000ffdb00430001010101010101010101010101010102010101" +
                    "0101020101010202020202020202020303040303030303020203040303040404040402030505040405040404" +
                    "04ffdb0043010101010101010201010204030203040404040404040404040404040404040404040404040404" +
                    "0404040404040404040404040404040404040404040404040404ffc000110800080008030111000211010311" +
                    "01ffc4001f0000010501010101010100000000000000000102030405060708090a0bffc400b5100002010303" +
                    "020403050504040000017d01020300041105122131410613516107227114328191a1082342b1c11552d1f024" +
                    "33627282090a161718191a25262728292a3435363738393a434445464748494a535455565758595a63646566" +
                    "6768696a737475767778797a838485868788898a92939495969798999aa2a3a4a5a6a7a8a9aab2b3b4b5b6b7" +
                    "b8b9bac2c3c4c5c6c7c8c9cad2d3d4d5d6d7d8d9dae1e2e3e4e5e6e7e8e9eaf1f2f3f4f5f6f7f8f9faffc400" +
                    "1f0100030101010101010101010000000000000102030405060708090a0bffc400b511000201020404030407" +
                    "05040400010277000102031104052131061241510761711322328108144291a1b1c109233352f0156272d10a" +
                    "162434e125f11718191a262728292a35363738393a434445464748494a535455565758595a63646566676869" +
                    "6a737475767778797a82838485868788898a92939495969798999aa2a3a4a5a6a7a8a9aab2b3b4b5b6b7b8b9" +
                    "bac2c3c4c5c6c7c8c9cad2d3d4d5d6d7d8d9dae2e3e4e5e6e7e8e9eaf2f3f4f5f6f7f8f9faffda000c030100" +
                    "02110311003f00fcb1f8b3fe9ffd81ff00312fed2fb57fd46ffb57eddf66ff00b09fdafed7ff00093ffd45fe" +
                    "d9ff000977fccc5ff0917fc5e1feaafd97dff35c7fdd33ff007a1fd6dfe47f6a7ed17ff9a3bfeea1ff00ba3f" +
                    "d7f575ffd9",
            )

        /** How far a decoded channel may sit from the source colour: JPEG is lossy and we scaled. */
        val tolerance = 45

        fun assertLooksLikeTheSourceImage(image: PixelBuffer) {
            val left = image.pixels[0]
            val right = image.pixels[image.width - 1]

            withClue("left edge should be red-ish, was r=${red(left)} g=${green(left)} b=${blue(left)}") {
                kotlin.math.abs(red(left) - 200) shouldBeLessThan tolerance
                kotlin.math.abs(blue(left) - 40) shouldBeLessThan tolerance
            }
            withClue("right edge should be blue-ish, was r=${red(right)} g=${green(right)} b=${blue(right)}") {
                kotlin.math.abs(red(right) - 40) shouldBeLessThan tolerance
                kotlin.math.abs(blue(right) - 200) shouldBeLessThan tolerance
            }
        }

        test("baseline decodes to the requested scale") {
            val image = decodeJpeg(baseline, maxWidth = 4)!!

            image.width shouldBe 4
            image.height shouldBe 4
        }

        test("baseline preserves the image's spatial layout") {
            assertLooksLikeTheSourceImage(decodeJpeg(baseline, maxWidth = 4)!!)
        }

        // The whole point of the scaled design: progressive is not a special case to decline.
        // 589 of 1180 real covers are progressive, so this test is the difference between fixing
        // the library grid and fixing half of it.
        //
        // It is also the regression guard for entropy-table scoping. This fixture defines AC table
        // 1 four times — the real table for the chroma first passes, then an end-of-band-only one
        // for the refinement scans — so a decoder that keeps a single table per id and reads it
        // after parsing decodes the chroma with the wrong table, lands no chroma AC at all, and
        // renders this image a flat purple-grey (r=130 g=79 b=131) instead of red and blue.
        test("progressive decodes, and agrees with baseline") {
            val image = decodeJpeg(progressive, maxWidth = 4)!!

            image.width shouldBe 4
            image.height shouldBe 4
            assertLooksLikeTheSourceImage(image)
        }

        // A run of sixteen zero coefficients is coded as ZRL — run 15, size 0 — and it is NOT an
        // end-of-block: the block continues after it. Reading it as an end-of-block leaves the rest
        // of that block's bits unread, so every block after it decodes from the wrong bit position.
        // The damage is invisible on a smooth image and total on a detailed one, which is why 378
        // of 1180 real covers decoded to nothing while the fixtures above stayed green.
        test("a block that codes a run of sixteen zeroes stays in step") {
            assertLooksLikeTheSourceImage(decodeJpeg(zeroRun, maxWidth = 2)!!)
        }

        test("a smaller request selects a coarser scale") {
            val image = decodeJpeg(baseline, maxWidth = 2)!!

            image.width shouldBe 2
            image.height shouldBe 2
        }

        // This is a DERIVATIVE decoder, not a general one — by design, per the spec. It reconstructs
        // only reduced scales, so a caller wanting something near full size is asking for a picture
        // it should simply serve the original bytes for. Declining says that plainly.
        test("a request near full size declines rather than pretending") {
            decodeJpeg(baseline, maxWidth = 4096).shouldBeNull()
        }

        test("a truncated file declines rather than throwing") {
            decodeJpeg(baseline.copyOfRange(0, 200), maxWidth = 4).shouldBeNull()
        }

        test("a file that is not a JPEG declines") {
            decodeJpeg(hexBytes("89504e470d0a1a0a"), maxWidth = 4).shouldBeNull()
        }
    })
