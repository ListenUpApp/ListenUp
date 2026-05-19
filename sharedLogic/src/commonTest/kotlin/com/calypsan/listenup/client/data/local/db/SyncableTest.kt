package com.calypsan.listenup.client.data.local.db

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SyncableTest {
    @Test
    fun syncState_hasExpectedValues() {
        // Verify all sync states exist
        val states = SyncState.entries.toList()
        assertEquals(4, states.size)
        assertTrue(states.contains(SyncState.SYNCED))
        assertTrue(states.contains(SyncState.NOT_SYNCED))
        assertTrue(states.contains(SyncState.SYNCING))
        assertTrue(states.contains(SyncState.CONFLICT))
    }

    @Test
    fun syncState_synced_representsCleanState() {
        val state = SyncState.SYNCED
        assertEquals("SYNCED", state.name)
    }

    @Test
    fun syncState_notSynced_representsLocalChanges() {
        val state = SyncState.NOT_SYNCED
        assertEquals("NOT_SYNCED", state.name)
    }
}
