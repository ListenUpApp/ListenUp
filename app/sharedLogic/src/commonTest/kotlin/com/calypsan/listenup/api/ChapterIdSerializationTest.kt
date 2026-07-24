package com.calypsan.listenup.api

import com.calypsan.listenup.core.ChapterId
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class ChapterIdSerializationTest :
    FunSpec({
        test("ChapterId round-trips through contractJson") {
            val id = ChapterId("ch-01H...")
            val encoded = contractJson.encodeToString(ChapterId.serializer(), id)
            val decoded = contractJson.decodeFromString(ChapterId.serializer(), encoded)
            decoded shouldBe id
        }
    })
