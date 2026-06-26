package com.calypsan.listenup.server.plugins

import com.calypsan.listenup.server.logging.ListenUpLoggerFactory
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.slf4j.event.Level

class JwtAuthTest :
    FunSpec({
        test("a failed JWT verification is logged at WARN without the token") {
            val capture = ListenUpLoggerFactory.installTestCapture()
            try {
                logJwtRejection("token verification failed")
                val event = capture.events.lastOrNull { it.loggerName.contains("JwtAuth") }.shouldNotBeNull()
                event.level shouldBe Level.WARN
                event.message.contains("token verification failed").shouldBeTrue()
            } finally {
                ListenUpLoggerFactory.removeTestCapture()
            }
        }
    })
