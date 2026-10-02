package com.calypsan.listenup.api

import com.calypsan.listenup.api.dto.hardcover.HardcoverBookCandidate
import com.calypsan.listenup.api.dto.hardcover.HardcoverBookMatch
import com.calypsan.listenup.api.dto.hardcover.HardcoverBookSync
import com.calypsan.listenup.api.dto.hardcover.HardcoverBrokenReason
import com.calypsan.listenup.api.dto.hardcover.HardcoverConnection
import com.calypsan.listenup.api.dto.hardcover.HardcoverHistory
import com.calypsan.listenup.api.dto.hardcover.HardcoverLinkFailure
import com.calypsan.listenup.api.dto.hardcover.HardcoverLinkPrompt
import com.calypsan.listenup.api.dto.hardcover.HardcoverShareMode
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

        test("Connected carries its share mode, and each mode round-trips") {
            HardcoverShareMode.entries.forEach { mode ->
                val state = HardcoverConnection.Connected(hardcoverUsername = "simon", since = 1L, shareMode = mode)
                val json = contractJson.encodeToString(HardcoverConnection.serializer(), state)
                contractJson.decodeFromString(HardcoverConnection.serializer(), json) shouldBe state
            }
        }

        test("the share modes are spelled on the wire as the server stores them") {
            contractJson.encodeToString(HardcoverShareMode.serializer(), HardcoverShareMode.AS_I_LISTEN) shouldBe "\"AS_I_LISTEN\""
            contractJson.encodeToString(HardcoverShareMode.serializer(), HardcoverShareMode.FINISHED_ONLY) shouldBe "\"FINISHED_ONLY\""
        }

        test("a Connected payload from a server that predates the share mode reads as As I listen") {
            val legacy = """{"type":"HardcoverConnection.Connected","hardcoverUsername":"simon","since":1}"""
            val decoded = contractJson.decodeFromString(HardcoverConnection.serializer(), legacy)
            decoded shouldBe HardcoverConnection.Connected(hardcoverUsername = "simon", since = 1L)
            (decoded as HardcoverConnection.Connected).shareMode shouldBe HardcoverShareMode.AS_I_LISTEN
        }

        test("a share mode this build doesn't know reads as As I listen instead of breaking the stream") {
            val future = """{"type":"HardcoverConnection.Connected","hardcoverUsername":"simon","since":1,"shareMode":"AFTER_AN_HOUR"}"""
            val decoded = contractJson.decodeFromString(HardcoverConnection.serializer(), future)
            (decoded as HardcoverConnection.Connected).shareMode shouldBe HardcoverShareMode.AS_I_LISTEN
        }
        listOf(
            HardcoverHistory.None,
            HardcoverHistory.Offer(bookCount = 74),
            HardcoverHistory.Available(bookCount = 74),
            HardcoverHistory.Sending(sentBooks = 23, totalBooks = 74),
            HardcoverHistory.Done(sentBooks = 70, needsMatchBooks = 4),
        ).forEach { history ->
            test("Connected carries $history, and it round-trips") {
                val state = HardcoverConnection.Connected(hardcoverUsername = "simon", since = 1L, history = history)
                val json = contractJson.encodeToString(HardcoverConnection.serializer(), state)
                contractJson.decodeFromString(HardcoverConnection.serializer(), json) shouldBe state
            }
        }

        test("the history states are spelled on the wire as pinned names") {
            contractJson.encodeToString(HardcoverHistory.serializer(), HardcoverHistory.None) shouldBe
                """{"type":"HardcoverHistory.None"}"""
            contractJson.encodeToString(HardcoverHistory.serializer(), HardcoverHistory.Offer(74)) shouldBe
                """{"type":"HardcoverHistory.Offer","bookCount":74}"""
            contractJson.encodeToString(HardcoverHistory.serializer(), HardcoverHistory.Done(70, 4)) shouldBe
                """{"type":"HardcoverHistory.Done","sentBooks":70,"needsMatchBooks":4}"""
        }

        test("a Connected payload from a server that predates history reads as no history") {
            val legacy = """{"type":"HardcoverConnection.Connected","hardcoverUsername":"simon","since":1}"""
            val decoded = contractJson.decodeFromString(HardcoverConnection.serializer(), legacy)
            (decoded as HardcoverConnection.Connected).history shouldBe HardcoverHistory.None
        }

        test("a history state this build doesn't know reads as no history instead of breaking the stream") {
            val future =
                """{"type":"HardcoverConnection.Connected","hardcoverUsername":"simon","since":1,""" +
                    """"history":{"type":"HardcoverHistory.Paused","bookCount":3}}"""
            val decoded = contractJson.decodeFromString(HardcoverConnection.serializer(), future)
            (decoded as HardcoverConnection.Connected).history shouldBe HardcoverHistory.None
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
            contractJson
                .decodeFromString(
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
