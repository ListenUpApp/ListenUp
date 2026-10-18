package com.calypsan.listenup.web.design

import androidx.compose.runtime.CompositionLocalProvider
import com.calypsan.listenup.api.dto.image.ImageFrame
import com.calypsan.listenup.client.core.image.frameLayout
import com.calypsan.listenup.web.MountRegistry
import com.calypsan.listenup.web.awaitFrame
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.flowOf
import org.w3c.dom.HTMLImageElement

/**
 * The web's framed photo: positioned by the shared `frameLayout`, in percentages of its box, so one
 * frame looks the same at every size; a centre crop until the image's size is known; and a failed load
 * reported, so a caller can fall back to the monogram that is the normal case.
 */
class FramedImgTest :
    FunSpec({
        val mounts = MountRegistry()
        afterTest { mounts.disposeAll() }

        test("a framed image is placed by frameLayout, in percentages of its box") {
            val url = portraitPngUrl(300, 400)
            val frame = ImageFrame(0.5, 0.2, 2.2)
            val host = mounts.mount { FramedImg(src = url, alt = "Maya", frame = frame) }
            awaitImagesApplied(host)

            val img = (host.querySelector("img.framed-img") as? HTMLImageElement).shouldNotBeNull()
            val layout = frameLayout(300.0, 400.0, 1.0, 1.0, frame)
            percentOf(img.style.width) shouldBe (layout.width * 100 plusOrMinus 0.001)
            percentOf(img.style.height) shouldBe (layout.height * 100 plusOrMinus 0.001)
            percentOf(img.style.left) shouldBe (layout.offsetX * 100 plusOrMinus 0.001)
            percentOf(img.style.top) shouldBe (layout.offsetY * 100 plusOrMinus 0.001)
            img.classList.contains("is-pending") shouldBe false
        }

        test("before the image loads it is a centre crop, so nothing jumps") {
            val host =
                mounts.mount { FramedImg(src = "/never-loads.png", alt = "", frame = ImageFrame(0.1, 0.1, 3.0)) }
            awaitFrame()
            (host.querySelector("img.framed-img") as HTMLImageElement).classList.contains("is-pending") shouldBe true
        }

        test("a failed load calls onError, so callers can fall back to initials") {
            var failed = false
            val host =
                mounts.mount {
                    FramedImg(src = "data:image/png;base64,AAAA", alt = "", frame = null, onError = { failed = true })
                }
            awaitImagesApplied(host)
            failed shouldBe true
        }

        test("the percentages are written by hand, so a whole number keeps its decimals") {
            formatPercent(1.0) shouldBe "100.0000%"
            formatPercent(-0.6029411) shouldBe "-60.2941%"
            formatPercent(0.0) shouldBe "0.0000%"
        }

        test("a user's avatar asks for that user's frame and draws through the framed primitive") {
            val asked = mutableListOf<String>()
            val host =
                mounts.mount {
                    CompositionLocalProvider(
                        LocalAvatarFrames provides { userId ->
                            asked += userId
                            flowOf(ImageFrame(0.5, 0.1, 2.0))
                        },
                    ) {
                        UserAvatar(userId = "u1", name = "Maya Lin", size = 96)
                    }
                }

            // Read at once, from the first composition: the avatar endpoint 404s in a spec, and the
            // monogram that replaces the photo on error is the right answer, just not this one.
            asked shouldBe listOf("u1")
            host.querySelector(".uav .framed img.framed-img").shouldNotBeNull()
        }

        test("a user's photo is decorative, like the monogram: the name beside it already says who") {
            val host = mounts.mount { UserAvatar(userId = "u1", name = "Maya Lin", size = 96) }

            // The attribute itself, empty: a missing alt has screen readers read out the URL instead.
            host.querySelector(".uav img.framed-img")?.getAttribute("alt") shouldBe ""
        }
    })
