package com.calypsan.listenup.api.dto.image

import com.calypsan.listenup.api.contractJson
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe

class ImageFrameContractTest :
    FunSpec({
        test("an ImageFraming round-trips through contractJson") {
            val framing = ImageFraming(imageVersion = "abc123", frame = ImageFrame(0.5, 0.25, 1.75))
            val json = contractJson.encodeToString(ImageFraming.serializer(), framing)
            contractJson.decodeFromString(ImageFraming.serializer(), json) shouldBe framing
        }

        test("Centered is today's centre crop") {
            ImageFrame.Centered shouldBe ImageFrame(focusX = 0.5, focusY = 0.5, zoom = 1.0)
        }

        test("validate accepts every boundary") {
            listOf(
                ImageFrame(0.0, 0.0, ImageFrame.MIN_ZOOM),
                ImageFrame(1.0, 1.0, ImageFrame.MAX_ZOOM),
                ImageFrame(0.5, 0.5, 1.0),
                ImageFrame(0.5, 0.5, 4.0),
            ).forEach { it.validate().shouldBeNull() }
        }

        test("validate rejects out-of-range and NaN values, naming the imageFrame field with the constant message") {
            listOf(
                ImageFrame(-0.01, 0.5, 1.0),
                ImageFrame(0.5, 1.01, 1.0),
                ImageFrame(0.5, 0.5, 0.99),
                ImageFrame(0.5, 0.5, 5.0),
                ImageFrame(Double.NaN, 0.5, 1.0),
                ImageFrame(0.5, Double.NaN, 1.0),
                ImageFrame(0.5, 0.5, Double.NaN),
            ).forEach { frame ->
                val error = frame.validate().shouldNotBeNull()
                error.field shouldBe "imageFrame"
                error.message shouldBe "That photo position couldn't be saved."
            }
        }

        test("an ImageFraming needs a version of 1 to 128 characters") {
            val frame = ImageFrame.Centered
            ImageFraming("a".repeat(128), frame).validate().shouldBeNull()
            ImageFraming("a".repeat(129), frame).validate().shouldNotBeNull().field shouldBe "imageFrame"
            ImageFraming("  ", frame).validate().shouldNotBeNull().field shouldBe "imageFrame"
            ImageFraming("v", ImageFrame(0.5, 0.5, 9.0)).validate().shouldNotBeNull()
        }

        test("contentVersionOf is the stem of a content-addressed path") {
            contentVersionOf("contributors/abc123.jpg") shouldBe "abc123"
            contentVersionOf("abc123.png") shouldBe "abc123"
        }

        test("a framing is for the image whose stem is its version, and never for no image") {
            val framing = ImageFraming("abc123", ImageFrame.Centered)
            framing.isFor("contributors/abc123.jpg") shouldBe true
            framing.isFor("contributors/def456.jpg") shouldBe false
            framing.isFor(null) shouldBe false
        }
    })
