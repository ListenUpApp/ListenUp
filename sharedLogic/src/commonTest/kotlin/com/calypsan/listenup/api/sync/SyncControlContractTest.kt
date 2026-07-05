package com.calypsan.listenup.api.sync

import com.calypsan.listenup.api.contractJson
import com.calypsan.listenup.api.error.InternalError
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

class SyncControlContractTest :
    FunSpec({

        test("CursorStale round-trips") {
            val original: SyncControl = SyncControl.CursorStale(lastKnownRevision = 999)
            val json = contractJson.encodeToString(SyncControl.serializer(), original)
            val decoded = contractJson.decodeFromString(SyncControl.serializer(), json)
            decoded shouldBe original
        }

        test("StreamError round-trips with typed AppError payload") {
            val err = InternalError(correlationId = "cid-xyz", cause = "NPE")
            val original: SyncControl = SyncControl.StreamError(error = err)
            val json = contractJson.encodeToString(SyncControl.serializer(), original)
            val decoded = contractJson.decodeFromString(SyncControl.serializer(), json)
            decoded.shouldBeInstanceOf<SyncControl.StreamError>()
            (decoded as SyncControl.StreamError).error.shouldBeInstanceOf<InternalError>()
            (decoded.error as InternalError).correlationId shouldBe "cid-xyz"
        }

        test("AccessChanged (bare, no scope) round-trips to scope=null — coarse") {
            val original: SyncControl = SyncControl.AccessChanged()
            val json = contractJson.encodeToString(SyncControl.serializer(), original)
            val decoded = contractJson.decodeFromString(SyncControl.serializer(), json)
            decoded shouldBe SyncControl.AccessChanged(scope = null)
        }

        test("a legacy bare AccessChanged frame (no scope key) decodes to scope=null") {
            // An older server emits the frame with no `scope` field — the additive default
            // must degrade to coarse, never fail to decode.
            val legacy = """{"type":"SyncControl.AccessChanged"}"""
            val decoded = contractJson.decodeFromString(SyncControl.serializer(), legacy)
            decoded shouldBe SyncControl.AccessChanged(scope = null)
        }

        test("scoped AccessChanged round-trips carrying affected entity ids") {
            val original: SyncControl =
                SyncControl.AccessChanged(
                    scope =
                        AccessScope(
                            collectionIds = listOf("col-1", "col-2"),
                            bookIds = listOf("book-9"),
                        ),
                )
            val json = contractJson.encodeToString(SyncControl.serializer(), original)
            val decoded = contractJson.decodeFromString(SyncControl.serializer(), json)
            decoded shouldBe original
        }

        test("an AccessChanged frame with an unknown key still decodes (forward-compat)") {
            val forward =
                """{"type":"SyncControl.AccessChanged","scope":{"collectionIds":["c"],"bookIds":[],"future":42}}"""
            val decoded = contractJson.decodeFromString(SyncControl.serializer(), forward)
            decoded shouldBe
                SyncControl.AccessChanged(
                    scope = AccessScope(collectionIds = listOf("c"), bookIds = emptyList()),
                )
        }

        test("UserDeleted round-trips") {
            val original: SyncControl = SyncControl.UserDeleted(reason = "removed by admin")
            val json = contractJson.encodeToString(SyncControl.serializer(), original)
            val decoded = contractJson.decodeFromString(SyncControl.serializer(), json)
            decoded shouldBe original
        }

        test("Stable discriminators") {
            val stale: SyncControl = SyncControl.CursorStale(0)
            val streamErr: SyncControl = SyncControl.StreamError(InternalError())
            val accessChanged: SyncControl = SyncControl.AccessChanged()
            contractJson
                .encodeToString(SyncControl.serializer(), stale)
                .contains("\"type\":\"SyncControl.CursorStale\"") shouldBe true
            contractJson
                .encodeToString(SyncControl.serializer(), streamErr)
                .contains("\"type\":\"SyncControl.StreamError\"") shouldBe true
            contractJson
                .encodeToString(SyncControl.serializer(), accessChanged)
                .contains("\"type\":\"SyncControl.AccessChanged\"") shouldBe true
            val userDeleted: SyncControl = SyncControl.UserDeleted()
            contractJson
                .encodeToString(SyncControl.serializer(), userDeleted)
                .contains("\"type\":\"SyncControl.UserDeleted\"") shouldBe true
        }
    })
