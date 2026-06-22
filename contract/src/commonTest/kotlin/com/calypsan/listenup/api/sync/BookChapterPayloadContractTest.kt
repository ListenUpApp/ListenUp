package com.calypsan.listenup.api.sync

import com.calypsan.listenup.api.contractJson
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class BookChapterPayloadContractTest :
    FunSpec({

        test("round-trips partTitle and bookTitle") {
            val payload =
                BookChapterPayload(
                    id = "c1",
                    title = "Chapter 1",
                    duration = 1000,
                    startTime = 0,
                    partTitle = "Part One",
                    bookTitle = "Book I",
                )
            val encoded = contractJson.encodeToString(BookChapterPayload.serializer(), payload)
            contractJson.decodeFromString(BookChapterPayload.serializer(), encoded) shouldBe payload
        }

        test("header fields default to null when absent from the wire") {
            val wire = """{"id":"c1","title":"Chapter 1","duration":1000,"startTime":0}"""
            val decoded = contractJson.decodeFromString(BookChapterPayload.serializer(), wire)
            decoded.partTitle shouldBe null
            decoded.bookTitle shouldBe null
        }
    })
