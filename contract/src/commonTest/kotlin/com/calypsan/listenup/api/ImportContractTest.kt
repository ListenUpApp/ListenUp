package com.calypsan.listenup.api

import com.calypsan.listenup.api.dto.imports.AbsItemRef
import com.calypsan.listenup.api.dto.imports.AbsUserMatch
import com.calypsan.listenup.api.dto.imports.ImportAnalysis
import com.calypsan.listenup.api.dto.imports.ImportEvent
import com.calypsan.listenup.api.dto.imports.ImportResult
import com.calypsan.listenup.api.dto.imports.ImportStatus
import com.calypsan.listenup.api.dto.imports.ImportSummary
import com.calypsan.listenup.api.dto.imports.MatchTier
import com.calypsan.listenup.api.dto.auth.UserId
import com.calypsan.listenup.api.error.ImportError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.core.AbsItemId
import com.calypsan.listenup.core.AbsUserId
import com.calypsan.listenup.core.ImportId
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class ImportContractTest :
    FunSpec({
        test("ImportAnalysis round-trips") {
            val a =
                ImportAnalysis(
                    userMatches = listOf(AbsUserMatch(AbsUserId("u1"), "simon", "simon@x", UserId("lu1"), MatchTier.STRONG)),
                    bookMatchCounts = mapOf(MatchTier.ASIN to 10, MatchTier.UNMATCHED to 2),
                    ambiguous = listOf(AbsItemRef(AbsItemId("i9"), "Ambig", null, null, "a/b")),
                    unmatched = listOf(AbsItemRef(AbsItemId("i7"), "Gone", "B00X", null, null)),
                )
            contractJson.decodeFromString<ImportAnalysis>(contractJson.encodeToString(a)) shouldBe a
        }

        test("ImportAnalysis round-trips with importableSessionCount") {
            val a =
                ImportAnalysis(
                    userMatches = emptyList(),
                    bookMatchCounts = mapOf(MatchTier.ASIN to 3),
                    ambiguous = emptyList(),
                    unmatched = emptyList(),
                    importableSessionCount = 17,
                )
            contractJson.decodeFromString<ImportAnalysis>(contractJson.encodeToString(a)) shouldBe a
        }

        test("ImportResult round-trips") {
            val r = ImportResult(importedCount = 8, skippedCount = 3, perUser = mapOf(UserId("lu1") to 8))
            contractJson.decodeFromString<ImportResult>(contractJson.encodeToString(r)) shouldBe r
        }

        test("ImportResult round-trips with sessionsImported") {
            val r = ImportResult(importedCount = 8, sessionsImported = 42, skippedCount = 3, perUser = emptyMap())
            contractJson.decodeFromString<ImportResult>(contractJson.encodeToString(r)) shouldBe r
        }

        test("ImportError additions round-trip polymorphically") {
            val e: AppResult<Unit> = AppResult.Failure(ImportError.ImportNotFound())
            contractJson.decodeFromString<AppResult<Unit>>(contractJson.encodeToString(e)) shouldBe e
        }

        test("ImportEvent variants round-trip") {
            val events =
                listOf<ImportEvent>(
                    ImportEvent.Parsing,
                    ImportEvent.Matching(3, 10),
                    ImportEvent.Analyzed(ImportSummary(ImportId("imp1"), 1L, ImportStatus.ANALYZED, 10, 2)),
                    ImportEvent.Applying(5, 8),
                    ImportEvent.Applied(ImportResult(importedCount = 8, skippedCount = 3, perUser = emptyMap())),
                    ImportEvent.Failed("boom"),
                )
            events.forEach { contractJson.decodeFromString<ImportEvent>(contractJson.encodeToString(it)) shouldBe it }
        }

        test("ImportEvent.Matching round-trips with currentItem and tallies") {
            val event: ImportEvent =
                ImportEvent.Matching(
                    done = 3,
                    total = 10,
                    currentItem = "The Way of Kings",
                    usersMatched = 2,
                    booksMatched = 3,
                )
            val decoded = contractJson.decodeFromString<ImportEvent>(contractJson.encodeToString(event))
            decoded shouldBe event
        }

        test("ImportEvent.Applying round-trips with currentItem and tally") {
            val event: ImportEvent =
                ImportEvent.Applying(
                    done = 5,
                    total = 8,
                    currentItem = "alice",
                    sessionsWritten = 5,
                )
            val decoded = contractJson.decodeFromString<ImportEvent>(contractJson.encodeToString(event))
            decoded shouldBe event
        }
    })
