package com.calypsan.listenup.client.domain.model

import com.calypsan.listenup.api.dto.match.LastMatch
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class BookMatchRecordTest :
    FunSpec({
        val match = LastMatch(receiptId = "r1", appliedAt = 1L, appliedBy = "u1", revision = 7L, changes = emptyList())

        test("the match is live while the book is still at the revision the match left it at") {
            BookMatchRecord(revision = 7L, lastMatch = match).liveMatch shouldBe match
        }

        test("any later change to the book retires the match") {
            BookMatchRecord(revision = 8L, lastMatch = match).liveMatch shouldBe null
        }

        test("a book with no match has nothing to undo") {
            BookMatchRecord(revision = 7L, lastMatch = null).liveMatch shouldBe null
        }
    })
