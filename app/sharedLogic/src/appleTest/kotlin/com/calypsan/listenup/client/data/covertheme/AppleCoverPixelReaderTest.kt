package com.calypsan.listenup.client.data.covertheme

import com.calypsan.listenup.client.domain.repository.ImageStorage
import com.calypsan.listenup.core.BookId
import dev.mokkery.answering.returns
import dev.mokkery.every
import dev.mokkery.matcher.any
import dev.mokkery.mock
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainOnly
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSData
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.create
import platform.Foundation.writeToFile

/** The iOS half of spec §4.4: a downloaded cover file becomes at most 64×64 unpremultiplied ARGB pixels. */
@OptIn(ExperimentalForeignApi::class)
class AppleCoverPixelReaderTest :
    FunSpec({
        fun pngFile(
            name: String,
            base64: String,
        ): String {
            val path = NSTemporaryDirectory() + name
            NSData.create(base64EncodedString = base64, options = 0u)!!.writeToFile(path, atomically = true)
            return path
        }

        test("a solid green cover decodes to its own colour, no larger than asked") {
            val pixels = decodeCoverThumbnail(pngFile("green.png", GREEN_8X8), maxEdge = 64).shouldNotBeNull()
            (pixels.width <= 64 && pixels.height <= 64) shouldBe true
            pixels.argb.toSet() shouldContainOnly setOf(0xFF3F7A55.toInt())
        }

        test("a transparent cover keeps its alpha at zero, so the extractor ignores it") {
            val pixels = decodeCoverThumbnail(pngFile("clear.png", CLEAR_8X8), maxEdge = 64).shouldNotBeNull()
            pixels.argb.all { it ushr 24 == 0 } shouldBe true
        }

        test("a missing file is no cover, not a crash") {
            decodeCoverThumbnail(NSTemporaryDirectory() + "missing.png", maxEdge = 64) shouldBe null
        }

        test("a book with no downloaded cover reads as null without touching the file system") {
            val storage = mock<ImageStorage>()
            every { storage.exists(any()) } returns false
            AppleCoverPixelReader(imageStorage = storage).read(BookId("b1"), "h1", 64) shouldBe null
        }
    })

private const val GREEN_8X8 =
    "iVBORw0KGgoAAAANSUhEUgAAAAgAAAAICAYAAADED76LAAAAEklEQVR42mOwrwr9jw8zjAwFAG6Vg0Ebc4V6AAAAAElFTkSuQmCC"
private const val CLEAR_8X8 =
    "iVBORw0KGgoAAAANSUhEUgAAAAgAAAAICAYAAADED76LAAAADUlEQVR42mNgGAUgAAABCAABXbcZDQAAAABJRU5ErkJggg=="
