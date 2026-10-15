package com.calypsan.listenup.web.design

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.w3c.files.Blob

/**
 * What the editor opens: a picked file or an existing photo, decoded to its upright size so the frame
 * the editor writes is the frame every renderer draws — or CouldNotOpen, never an exception, so the
 * editor can say so and offer another.
 */
class PhotoDecodeTest :
    FunSpec({
        test("a real PNG decodes to its own size") {
            val decoded = decodePhoto(pngBlob(300, 400))
            val ready = decoded.shouldBeInstanceOf<DecodedPhoto.Ready>()
            ready.width shouldBe 300
            ready.height shouldBe 400
        }

        test("bytes that are not an image are CouldNotOpen, not an exception") {
            decodePhoto(Blob(arrayOf("not an image"))) shouldBe DecodedPhoto.CouldNotOpen
        }

        test("an existing photo at a URL decodes to its size") {
            val url = portraitPngUrl(120, 90)
            val ready = decodePhotoAt(url).shouldBeInstanceOf<DecodedPhoto.Ready>()
            ready.url shouldBe url
            ready.width shouldBe 120
            ready.height shouldBe 90
        }

        test("a URL that does not load is CouldNotOpen") {
            decodePhotoAt("data:image/png;base64,AAAA") shouldBe DecodedPhoto.CouldNotOpen
        }
    })
