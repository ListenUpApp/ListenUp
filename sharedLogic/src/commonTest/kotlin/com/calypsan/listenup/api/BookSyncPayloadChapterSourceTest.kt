package com.calypsan.listenup.api

import com.calypsan.listenup.api.sync.ChapterSource
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class BookSyncPayloadChapterSourceTest :
    FunSpec({
        test("ChapterSource has EMBEDDED, AUDNEXUS, USER in that order; default is EMBEDDED") {
            ChapterSource.valueOf("EMBEDDED") shouldBe ChapterSource.EMBEDDED
            ChapterSource.entries.map { it.name } shouldBe listOf("EMBEDDED", "AUDNEXUS", "USER")
        }
    })
