package com.calypsan.listenup.web.design

import com.calypsan.listenup.client.domain.model.ShelfCover
import com.calypsan.listenup.web.MountRegistry
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/** A shelf's mosaic is always four tiles, covers first then the empty tone, and decorative. */
class ShelfMosaicTest :
    FunSpec({
        val mounts = MountRegistry()
        afterTest { mounts.disposeAll() }

        test("two covers draw two images and two empty tiles") {
            val host = mounts.mount { ShelfMosaic(listOf(ShelfCover("b1", null, "h1"), ShelfCover("b2", null, null))) }
            host.querySelectorAll(".mosaic img").length shouldBe 2
            host.querySelectorAll(".mosaic-tile.is-empty").length shouldBe 2
        }

        test("the mosaic is hidden from assistive technology") {
            val host = mounts.mount { ShelfMosaic(emptyList()) }
            host.querySelector(".mosaic")!!.getAttribute("aria-hidden") shouldBe "true"
        }
    })
