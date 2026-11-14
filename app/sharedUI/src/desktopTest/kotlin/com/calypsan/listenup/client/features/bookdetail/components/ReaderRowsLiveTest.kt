package com.calypsan.listenup.client.features.bookdetail.components

import com.calypsan.listenup.client.domain.readers.ReaderLine
import com.calypsan.listenup.client.domain.readers.ReaderLineKind
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/** Someone else listening now wears the same live ring in Readers as you do: "Listening now · 20%". */
class ReaderRowsLiveTest :
    FunSpec({
        fun reading(
            name: String,
            isYou: Boolean,
            isLive: Boolean,
        ) = ReaderLine(
            userId = name.lowercase(),
            name = name,
            isYou = isYou,
            kind = ReaderLineKind.Reading(progressPct = 20, isLive = isLive),
        )

        test("another reader who is listening now is a live row") {
            val row = listOf(reading("Maya", isYou = false, isLive = true)).toReaderRows(nowMs = 0L).single()
            row.isLive shouldBe true
            row.progressPct shouldBe 20
        }

        test("another reader partway through but not listening now is not live") {
            listOf(reading("Priya", isYou = false, isLive = false)).toReaderRows(nowMs = 0L).single().isLive shouldBe false
        }
    })
