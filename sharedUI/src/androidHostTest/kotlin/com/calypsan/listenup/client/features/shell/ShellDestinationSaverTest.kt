package com.calypsan.listenup.client.features.shell

import androidx.compose.runtime.saveable.SaverScope
import kotlin.test.Test
import kotlin.test.assertEquals

class ShellDestinationSaverTest {
    @Test
    fun roundTripsEveryDestination() {
        val scope = SaverScope { true }
        for (destination in ShellDestination.entries) {
            val saved = with(shellDestinationSaver) { scope.save(destination) }
            val restored = shellDestinationSaver.restore(saved!!)
            assertEquals(destination, restored)
        }
    }
}
