package com.calypsan.listenup.api.sync

import com.calypsan.listenup.api.contractJson
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.encodeToString

/**
 * What a version-skewed peer does with [BookSeriesPayload.sequence], now that it is a number.
 *
 * `sequence` changed shape on the wire, and the two halves of a self-hosted install do not update
 * together: the phone updates itself from Play while the server waits to be updated by hand. The
 * skew window is the normal case here, not the exotic one.
 *
 * The stakes are higher than one odd-looking book. The client's cursored pull decodes a page with
 * `items.map { decodeFromString(...) }` and no per-row catch (`SyncPage.toPage`), so a row that
 * fails to decode throws out of the whole page — and the cursor never advances, so the books domain
 * retries that same page forever. One unreadable sequence stalls sync entirely.
 *
 * The old shape is written here as literal wire text rather than as a mirror `@Serializable` class,
 * for two reasons: a legacy DTO in `api..` outside `commonMain` violates the architecture rule that
 * keeps DTOs in one place, and the bytes are what actually arrive — asserting against them is the
 * stronger claim.
 */
class SeriesSequenceWireSkewTest :
    FunSpec({

        /** The old wire shape: `sequence` as a quoted label. */
        fun oldWire(sequence: String?) =
            if (sequence == null) {
                """{"id":"s1","name":"The Expanse","sequence":null}"""
            } else {
                """{"id":"s1","name":"The Expanse","sequence":"$sequence"}"""
            }

        test("a new server's number is readable where a String was expected") {
            val wire = contractJson.encodeToString(BookSeriesPayload("s1", "The Expanse", 1.5))
            wire shouldBe """{"id":"s1","name":"The Expanse","sequence":1.5}"""

            // Decoding through the String deserializer is exactly what an older client's
            // `sequence: String?` property does; `isLenient` accepts the unquoted number.
            val asOldClientSeesIt =
                contractJson.decodeFromString(
                    MapSerializer(String.serializer(), String.serializer()),
                    wire,
                )
            asOldClientSeesIt["sequence"] shouldBe "1.5"
        }

        test("an old server's numeric string is readable by a new client") {
            contractJson.decodeFromString<BookSeriesPayload>(oldWire("1.5")).sequence shouldBe 1.5
        }

        // ⛔ The case that made the tolerant serializer necessary, and the only one `isLenient`
        // cannot handle: an omnibus label is not a number under any lenient reading of JSON.
        // Without SeriesSequenceSerializer this throws JsonDecodingException — and because the pull
        // has no per-row catch, it takes the whole page and every retry of it with it. A phone that
        // updated itself overnight, against a server not yet migrated, would simply stop syncing.
        //
        // The reference library holds 7 such rows, all real omnibus volumes: "1-3" (Bunnicula, The
        // Divine Comedy, Final Quest), "1-5" (The Once and Future King), "1-6" (Sherlock Holmes),
        // "1-2" (The Jungle Books), and "1 Parts 1-2" (Forge of Darkness — genuinely book 1, its
        // tag conflating series position with the Audible edition's two-part packaging).
        test("an old server's OMNIBUS label is readable, and files at its first book") {
            contractJson.decodeFromString<BookSeriesPayload>(oldWire("1-3")).sequence shouldBe 1.0
            contractJson.decodeFromString<BookSeriesPayload>(oldWire("1-6")).sequence shouldBe 1.0
            contractJson.decodeFromString<BookSeriesPayload>(oldWire("1 Parts 1-2")).sequence shouldBe 1.0
        }

        test("an old server's unnumbered label reads as absent, not as book zero") {
            // A bare CAST would make this 0.0 and sort it ahead of book 1 forever; the shared
            // parseSeriesSequence refuses instead, exactly as both migrations do.
            contractJson.decodeFromString<BookSeriesPayload>(oldWire("Prequel")).sequence shouldBe null
        }

        test("null survives in both directions") {
            contractJson.decodeFromString<BookSeriesPayload>(oldWire(null)).sequence shouldBe null
            contractJson.encodeToString(BookSeriesPayload("s1", "The Expanse", null)) shouldBe
                """{"id":"s1","name":"The Expanse","sequence":null}"""
        }

        // Tolerance is a reading concern only. Emitting the legacy shape as well would keep the
        // ambiguity alive on the wire indefinitely, so what goes out is always a bare number.
        test("the new side always WRITES a number, never the legacy string") {
            contractJson.encodeToString(BookSeriesPayload("s1", "The Expanse", 1.0)) shouldBe
                """{"id":"s1","name":"The Expanse","sequence":1.0}"""
        }
    })
