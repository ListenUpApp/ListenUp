package com.calypsan.listenup.server.logging

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.slf4j.event.Level

class PlainFormatterTest :
    FunSpec({
        test("formatPlain renders only the simple class name, not the package") {
            val out =
                formatPlain(
                    level = Level.INFO,
                    loggerName = "com.calypsan.listenup.server.scanner.ScanOrchestrator",
                    message = "started",
                    throwable = null,
                )

            out shouldContain " ScanOrchestrator - started"
            out shouldNotContain "com.calypsan"
        }
    })
