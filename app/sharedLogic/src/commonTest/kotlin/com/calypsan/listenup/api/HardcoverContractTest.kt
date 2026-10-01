package com.calypsan.listenup.api

import com.calypsan.listenup.api.dto.hardcover.HardcoverBookCandidate
import com.calypsan.listenup.api.dto.hardcover.HardcoverBookMatch
import com.calypsan.listenup.api.dto.hardcover.HardcoverBookSync
import com.calypsan.listenup.api.dto.hardcover.HardcoverBrokenReason
import com.calypsan.listenup.api.dto.hardcover.HardcoverConnection
import com.calypsan.listenup.api.dto.hardcover.HardcoverLinkFailure
import com.calypsan.listenup.api.dto.hardcover.HardcoverLinkPrompt
import com.calypsan.listenup.api.dto.hardcover.HardcoverSyncProblem
import com.calypsan.listenup.api.error.AppError
import com.calypsan.listenup.api.error.HardcoverError
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/** Pins the Hardcover connection wire shapes: every state and error round-trips with a stable discriminator. */
class HardcoverContractTest :
    FunSpec({

        val prompt =
            HardcoverLinkPrompt(
                userCode = "ABCD-1234",
                verificationUri = "https://hardcover.app/link",
                verificationUriComplete = "https://hardcover.app/link?code=ABCD-1234",
                expiresAt = 1_780_000_000_000L,
            )

        listOf<HardcoverConnection>(
            HardcoverConnection.NotOffered,
            HardcoverConnection.NotConnected(lastLinkFailure = null),
            HardcoverConnection.NotConnected(lastLinkFailure = HardcoverLinkFailure.DENIED),
            HardcoverConnection.Linking(prompt),
            HardcoverConnection.Connected(hardcoverUsername = "simon", since = 1_780_000_000_000L),
            HardcoverConnection.Broken(reason = HardcoverBrokenReason.REVOKED, hardcoverUsername = "simon"),
            HardcoverConnection.Broken(reason = HardcoverBrokenReason.CANNOT_DECRYPT, hardcoverUsername = null),
        ).forEach { state ->
            test("$state round-trips") {
                val json = contractJson.encodeToString(HardcoverConnection.serializer(), state)
                contractJson.decodeFromString(HardcoverConnection.serializer(), json) shouldBe state
            }
        }

        test("a Broken payload from before the username was carried decodes with no name") {
            val legacy = """{"type":"HardcoverConnection.Broken","reason":"REVOKED"}"""
            contractJson.decodeFromString(HardcoverConnection.serializer(), legacy) shouldBe
                HardcoverConnection.Broken(reason = HardcoverBrokenReason.REVOKED, hardcoverUsername = null)
        }

        listOf<Pair<String, AppError>>(
            "HardcoverError.NotConfigured" to HardcoverError.NotConfigured(),
            "HardcoverError.Unavailable" to HardcoverError.Unavailable(),
            "HardcoverError.ConnectionBroken" to HardcoverError.ConnectionBroken(),
            "HardcoverError.AlreadyConnected" to HardcoverError.AlreadyConnected(),
            "HardcoverError.NotConnected" to HardcoverError.NotConnected(),
        ).forEach { (discriminator, error) ->
            test("$discriminator round-trips through AppError") {
                val json = contractJson.encodeToString(AppError.serializer(), error)
                json.contains("\"$discriminator\"") shouldBe true
                contractJson.decodeFromString(AppError.serializer(), json) shouldBe error
            }
        }

        test("only Unavailable is retryable") {
            HardcoverError.Unavailable().isRetryable shouldBe true
            HardcoverError.NotConfigured().isRetryable shouldBe false
            HardcoverError.AlreadyConnected().isRetryable shouldBe false
            HardcoverError.ConnectionBroken().isRetryable shouldBe false
            HardcoverError.NotConnected().isRetryable shouldBe false
        }

        test("a catalog candidate round-trips, with and without an edition") {
            listOf(
                HardcoverBookCandidate(
                    hcBookId = 427_578L,
                    hcEditionId = 9_001L,
                    title = "Project Hail Mary",
                    authors = listOf("Andy Weir"),
                    releaseYear = 2021,
                ),
                HardcoverBookCandidate(hcBookId = 1L, hcEditionId = null, title = "Untitled", authors = emptyList(), releaseYear = null),
            ).forEach { candidate ->
                val json = contractJson.encodeToString(HardcoverBookCandidate.serializer(), candidate)
                contractJson.decodeFromString(HardcoverBookCandidate.serializer(), json) shouldBe candidate
            }
        }

        test("a catalog candidate's wire keys are pinned") {
            val json =
                contractJson.encodeToString(
                    HardcoverBookCandidate.serializer(),
                    HardcoverBookCandidate(hcBookId = 1L, hcEditionId = 2L, title = "T", authors = listOf("A"), releaseYear = 2020),
                )
            listOf("hcBookId", "hcEditionId", "title", "authors", "releaseYear").forEach { key ->
                json.contains("\"$key\"") shouldBe true
            }
        }

        test("a Connected with sync health round-trips") {
            val state =
                HardcoverConnection.Connected(
                    hardcoverUsername = "simon",
                    since = 1_780_000_000_000L,
                    lastSyncedAt = 1_780_000_600_000L,
                    isSyncing = true,
                    syncProblem = HardcoverSyncProblem.PUSH_STALLED,
                )
            val json = contractJson.encodeToString(HardcoverConnection.serializer(), state)
            contractJson.decodeFromString(HardcoverConnection.serializer(), json) shouldBe state
        }

        test("a Connected from a server that predates sync health decodes as never synced, idle and healthy") {
            val legacy = """{"type":"HardcoverConnection.Connected","hardcoverUsername":"simon","since":5}"""
            contractJson.decodeFromString(HardcoverConnection.serializer(), legacy) shouldBe
                HardcoverConnection.Connected(hardcoverUsername = "simon", since = 5L)
        }

        test("a sync problem a client doesn't know yet reads as no problem, never as a failure to decode") {
            val future =
                """{"type":"HardcoverConnection.Connected","hardcoverUsername":"simon","since":5,"syncProblem":"SOMETHING_NEW"}"""
            contractJson.decodeFromString(HardcoverConnection.serializer(), future) shouldBe
                HardcoverConnection.Connected(hardcoverUsername = "simon", since = 5L, syncProblem = null)
        }

        test("a candidate carries its rating count, and one from an older server has none") {
            val candidate =
                HardcoverBookCandidate(427_578L, 9_001L, "Project Hail Mary", listOf("Andy Weir"), 2021, ratingsCount = 8_107)
            val json = contractJson.encodeToString(HardcoverBookCandidate.serializer(), candidate)
            contractJson.decodeFromString(HardcoverBookCandidate.serializer(), json) shouldBe candidate
            contractJson.decodeFromString(
                HardcoverBookCandidate.serializer(),
                """{"hcBookId":1,"title":"Untitled"}""",
            ).ratingsCount shouldBe null
        }

        listOf<HardcoverBookMatch>(
            HardcoverBookMatch.Unmatched,
            HardcoverBookMatch.NeedsMatch,
            HardcoverBookMatch.Linked(
                hcBookId = 427_578L,
                hcEditionId = 9_001L,
                title = "Project Hail Mary",
                authors = listOf("Andy Weir"),
                releaseYear = 2021,
                chosenByYou = true,
                sync = HardcoverBookSync.WAITING,
            ),
            HardcoverBookMatch.Linked(hcBookId = 1L),
        ).forEach { match ->
            test("$match round-trips") {
                val json = contractJson.encodeToString(HardcoverBookMatch.serializer(), match)
                contractJson.decodeFromString(HardcoverBookMatch.serializer(), json) shouldBe match
            }
        }
    })
