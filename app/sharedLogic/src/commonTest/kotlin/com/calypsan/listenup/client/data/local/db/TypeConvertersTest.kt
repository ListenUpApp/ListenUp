package com.calypsan.listenup.client.data.local.db

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class TypeConvertersTest :
    FunSpec({
        val converters = Converters()

        test("fromSyncState_synced_returnsName") {
            converters.fromSyncState(SyncState.SYNCED) shouldBe "SYNCED"
        }

        test("fromSyncState_notSynced_returnsName") {
            converters.fromSyncState(SyncState.NOT_SYNCED) shouldBe "NOT_SYNCED"
        }

        test("fromSyncState_syncing_returnsName") {
            converters.fromSyncState(SyncState.SYNCING) shouldBe "SYNCING"
        }

        test("fromSyncState_conflict_returnsName") {
            converters.fromSyncState(SyncState.CONFLICT) shouldBe "CONFLICT"
        }

        test("toSyncState_syncedName_returnsSynced") {
            converters.toSyncState("SYNCED") shouldBe SyncState.SYNCED
        }

        test("toSyncState_notSyncedName_returnsNotSynced") {
            converters.toSyncState("NOT_SYNCED") shouldBe SyncState.NOT_SYNCED
        }

        test("toSyncState_syncingName_returnsSyncing") {
            converters.toSyncState("SYNCING") shouldBe SyncState.SYNCING
        }

        test("toSyncState_conflictName_returnsConflict") {
            converters.toSyncState("CONFLICT") shouldBe SyncState.CONFLICT
        }

        test("syncStateConversion_roundTrip_preservesValue") {
            SyncState.entries.forEach { state ->
                val name = converters.fromSyncState(state)
                val restored = converters.toSyncState(name)
                restored shouldBe state
            }
        }
    })
