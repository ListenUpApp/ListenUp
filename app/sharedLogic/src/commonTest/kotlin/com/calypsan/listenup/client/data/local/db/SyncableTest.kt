package com.calypsan.listenup.client.data.local.db

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class SyncableTest :
    FunSpec({
        test("syncState_hasExpectedValues") {
            // Verify all sync states exist
            val states = SyncState.entries.toList()
            states.size shouldBe 4
            states.contains(SyncState.SYNCED) shouldBe true
            states.contains(SyncState.NOT_SYNCED) shouldBe true
            states.contains(SyncState.SYNCING) shouldBe true
            states.contains(SyncState.CONFLICT) shouldBe true
        }

        test("syncState_synced_representsCleanState") {
            val state = SyncState.SYNCED
            state.name shouldBe "SYNCED"
        }

        test("syncState_notSynced_representsLocalChanges") {
            val state = SyncState.NOT_SYNCED
            state.name shouldBe "NOT_SYNCED"
        }
    })
