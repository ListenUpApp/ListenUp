package com.calypsan.listenup.client.design.components

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Test

/** The wrapping grid's column count flows with the width, like `GridCells.Adaptive`, and is never zero. */
class AdaptiveCardGridTest {
    @Test
    fun columnsFlowWithWidth() {
        assertEquals(1, adaptiveColumns(available = 100.dp, minCell = 150.dp, spacing = 16.dp))
        assertEquals(2, adaptiveColumns(available = 316.dp, minCell = 150.dp, spacing = 16.dp))
        assertEquals(6, adaptiveColumns(available = 1000.dp, minCell = 150.dp, spacing = 16.dp))
    }
}
