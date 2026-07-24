package com.calypsan.listenup.api

import com.calypsan.listenup.api.dto.scanner.ScanPhase
import com.calypsan.listenup.api.event.ScanBookRef
import com.calypsan.listenup.api.event.ScanEvent
import com.calypsan.listenup.core.LibraryId
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json

class ScanEventContractTest :
    FunSpec({
        val json = Json { ignoreUnknownKeys = true }

        test("ScanEvent.Progress round-trips with the enriched fields") {
            val event: ScanEvent =
                ScanEvent.Progress(
                    correlationId = "c1",
                    libraryId = LibraryId("lib1"),
                    phase = ScanPhase.ANALYZING,
                    filesWalked = 1647,
                    booksAnalyzed = 174,
                    errors = 2,
                    totalFiles = 1647,
                    booksTotal = 980,
                    authorsMatched = 21,
                    totalDurationMs = 252_000_000L,
                    currentFile = "Sanderson, Brandon/Mistborn/01.m4b",
                    recentBooks = listOf(ScanBookRef("Mistborn", "Brandon Sanderson")),
                )
            val decoded = json.decodeFromString(ScanEvent.serializer(), json.encodeToString(ScanEvent.serializer(), event))
            decoded shouldBe event
            (decoded as ScanEvent.Progress).booksTotal shouldBe 980
        }
    })
