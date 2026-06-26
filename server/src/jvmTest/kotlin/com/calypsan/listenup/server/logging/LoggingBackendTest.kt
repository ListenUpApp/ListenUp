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

        test("LISTENUP_LOG_LEVEL=DEBUG enables debug on app loggers") {
            val factory =
                ListenUpLoggerFactory(
                    isJsonFormat = false,
                    levelConfig = LogLevelConfig.fromEnv(mapOf("LISTENUP_LOG_LEVEL" to "DEBUG")),
                )
            factory.getLogger("com.calypsan.listenup.server.sync.SyncRoutes").isDebugEnabled.shouldBeTrue()
        }

        test("unset or unparseable LISTENUP_LOG_LEVEL defaults to INFO") {
            val unset = ListenUpLoggerFactory(isJsonFormat = false, levelConfig = LogLevelConfig.fromEnv(emptyMap()))
            unset.getLogger("com.calypsan.x").isInfoEnabled.shouldBeTrue()
            unset.getLogger("com.calypsan.x").isDebugEnabled.shouldBeFalse()

            val garbage =
                ListenUpLoggerFactory(
                    isJsonFormat = false,
                    levelConfig = LogLevelConfig.fromEnv(mapOf("LISTENUP_LOG_LEVEL" to "LOUD")),
                )
            garbage.getLogger("com.calypsan.x").isInfoEnabled.shouldBeTrue()
            garbage.getLogger("com.calypsan.x").isDebugEnabled.shouldBeFalse()
        }

        test("an empty-suffix LISTENUP_LOG_LEVEL_ key is ignored (no silent catch-all)") {
            val cfg = LogLevelConfig.fromEnv(mapOf("LISTENUP_LOG_LEVEL" to "INFO", "LISTENUP_LOG_LEVEL_" to "TRACE"))
            val factory = ListenUpLoggerFactory(isJsonFormat = false, levelConfig = cfg)
            // the malformed key must NOT enable TRACE/DEBUG everywhere — the INFO default stands
            factory.getLogger("com.calypsan.listenup.server.anything").isDebugEnabled.shouldBeFalse()
            factory.getLogger("com.calypsan.listenup.server.anything").isInfoEnabled.shouldBeTrue()
        }

        test("per-prefix override targets only matching loggers") {
            val cfg =
                LogLevelConfig.fromEnv(
                    mapOf(
                        "LISTENUP_LOG_LEVEL" to "WARN",
                        "LISTENUP_LOG_LEVEL_com_calypsan_listenup_server_sync" to "DEBUG",
                    ),
                )
            val factory = ListenUpLoggerFactory(isJsonFormat = false, levelConfig = cfg)
            factory.getLogger("com.calypsan.listenup.server.sync.SyncRoutes").isDebugEnabled.shouldBeTrue()
            factory.getLogger("com.calypsan.listenup.server.book.BookServiceImpl").isInfoEnabled.shouldBeFalse()
            factory.getLogger("com.calypsan.listenup.server.book.BookServiceImpl").isWarnEnabled.shouldBeTrue()
        }

        test("netty/jetty WARN floor still wins over a broad DEBUG default") {
            val cfg = LogLevelConfig.fromEnv(mapOf("LISTENUP_LOG_LEVEL" to "DEBUG"))
            val factory = ListenUpLoggerFactory(isJsonFormat = false, levelConfig = cfg)
            factory.getLogger("io.netty.some.Class").isInfoEnabled.shouldBeFalse()
            factory.getLogger("io.netty.some.Class").isWarnEnabled.shouldBeTrue()
        }
    })
