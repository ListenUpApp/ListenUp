package com.calypsan.listenup.api

import com.calypsan.listenup.api.sync.CoverSource
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class CoverSourceTest :
    FunSpec({
        test("CoverSource has the four provenance values with stable lowercase wire names") {
            CoverSource.entries.map { it.name.lowercase() } shouldBe
                listOf("filesystem", "embedded", "uploaded", "enriched")
        }
    })
