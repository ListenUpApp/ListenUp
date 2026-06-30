package com.calypsan.listenup.server.logging

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

private class SampleSubsystem

class LoggerForTest :
    FunSpec({
        test("loggerFor derives the fully-qualified class name") {
            loggerFor<SampleSubsystem>().name shouldBe
                "com.calypsan.listenup.server.logging.SampleSubsystem"
        }
    })
