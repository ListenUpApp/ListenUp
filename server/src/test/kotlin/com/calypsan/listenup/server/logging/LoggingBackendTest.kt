package com.calypsan.listenup.server.logging

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.slf4j.MDC
import org.slf4j.event.Level

class LoggingBackendTest :
    FunSpec({

        test("json format flag produces JSON format, absent flag produces plain format") {
            val jsonFactory = ListenUpLoggerFactory(isJsonFormat = true)
            jsonFactory.isJsonFormat.shouldBeTrue()

            val plainFactory = ListenUpLoggerFactory(isJsonFormat = false)
            plainFactory.isJsonFormat.shouldBeFalse()
        }

        test("io.netty and org.eclipse.jetty loggers suppress INFO but emit WARN") {
            val factory = ListenUpLoggerFactory(isJsonFormat = false)

            val nettyLogger = factory.getLogger("io.netty.some.Class")
            nettyLogger.isInfoEnabled.shouldBeFalse()
            nettyLogger.isWarnEnabled.shouldBeTrue()

            val jettyLogger = factory.getLogger("org.eclipse.jetty.server.HttpChannel")
            jettyLogger.isInfoEnabled.shouldBeFalse()
            jettyLogger.isWarnEnabled.shouldBeTrue()

            val rootLogger = factory.getLogger("com.calypsan.listenup.something")
            rootLogger.isInfoEnabled.shouldBeTrue()
            rootLogger.isWarnEnabled.shouldBeTrue()
        }

        test("correlationId from MDC appears in captured plain output") {
            val capture = ListenUpLoggerFactory.installTestCapture()
            MDC.put("correlationId", "req-abc-123")
            try {
                val logger = org.slf4j.LoggerFactory.getLogger("test.CorrelationTest")
                logger.info("Testing MDC propagation")

                val event = capture.events.firstOrNull { it.message == "Testing MDC propagation" }.shouldNotBeNull()
                event.level shouldBe Level.INFO
                event.mdc["correlationId"] shouldBe "req-abc-123"
            } finally {
                MDC.remove("correlationId")
                ListenUpLoggerFactory.removeTestCapture()
            }
        }
    })
