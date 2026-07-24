package com.calypsan.listenup.client.features.shell

import androidx.compose.runtime.saveable.SaverScope
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class ShellDestinationSaverTest :
    FunSpec({
        test("roundTripsEveryDestination") {
            val scope = SaverScope { true }
            for (destination in ShellDestination.entries) {
                val saved = with(shellDestinationSaver) { scope.save(destination) }
                val restored = shellDestinationSaver.restore(saved!!)
                restored shouldBe destination
            }
        }
    })
