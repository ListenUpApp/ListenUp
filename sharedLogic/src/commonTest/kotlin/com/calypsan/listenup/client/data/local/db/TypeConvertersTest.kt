package com.calypsan.listenup.client.data.local.db

import kotlin.test.Test
import kotlin.test.assertEquals

class TypeConvertersTest {
    private val converters = Converters()

    @Test
    fun fromSyncState_synced_returnsName() {
        assertEquals("SYNCED", converters.fromSyncState(SyncState.SYNCED))
    }

    @Test
    fun fromSyncState_notSynced_returnsName() {
        assertEquals("NOT_SYNCED", converters.fromSyncState(SyncState.NOT_SYNCED))
    }

    @Test
    fun fromSyncState_syncing_returnsName() {
        assertEquals("SYNCING", converters.fromSyncState(SyncState.SYNCING))
    }

    @Test
    fun fromSyncState_conflict_returnsName() {
        assertEquals("CONFLICT", converters.fromSyncState(SyncState.CONFLICT))
    }

    @Test
    fun toSyncState_syncedName_returnsSynced() {
        assertEquals(SyncState.SYNCED, converters.toSyncState("SYNCED"))
    }

    @Test
    fun toSyncState_notSyncedName_returnsNotSynced() {
        assertEquals(SyncState.NOT_SYNCED, converters.toSyncState("NOT_SYNCED"))
    }

    @Test
    fun toSyncState_syncingName_returnsSyncing() {
        assertEquals(SyncState.SYNCING, converters.toSyncState("SYNCING"))
    }

    @Test
    fun toSyncState_conflictName_returnsConflict() {
        assertEquals(SyncState.CONFLICT, converters.toSyncState("CONFLICT"))
    }

    @Test
    fun syncStateConversion_roundTrip_preservesValue() {
        SyncState.entries.forEach { state ->
            val name = converters.fromSyncState(state)
            val restored = converters.toSyncState(name)
            assertEquals(state, restored)
        }
    }
}
