package com.calypsan.listenup.client.design.components

import com.calypsan.listenup.api.dto.image.ImageFrame
import com.calypsan.listenup.client.design.image.FramedAlignment
import com.calypsan.listenup.client.design.image.FramedCrop
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class ContributorCoverImageFrameTest :
    FunSpec({
        test("a contributor photo's default scale and alignment come from its frame") {
            val frame = ImageFrame(0.3, 0.2, 1.8)
            contributorContentScale(frame) shouldBe FramedCrop(frame)
            contributorAlignment(frame) shouldBe FramedAlignment(frame)
        }

        test("no frame is today's centre crop") {
            contributorContentScale(null) shouldBe FramedCrop(null)
            contributorAlignment(null) shouldBe FramedAlignment(null)
        }
    })
