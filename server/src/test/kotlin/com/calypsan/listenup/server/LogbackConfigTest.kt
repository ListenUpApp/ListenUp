package com.calypsan.listenup.server

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.encoder.PatternLayoutEncoder
import ch.qos.logback.classic.joran.JoranConfigurator
import ch.qos.logback.core.ConsoleAppender
import ch.qos.logback.core.status.Status
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import net.logstash.logback.encoder.LogstashEncoder

/**
 * Pins the Janino-free logging contract: the env var maps to a static config
 * file (no runtime bytecode generation), and both config files are valid
 * standalone logback configurations. This is the native-image-relevant property —
 * a Janino `<if>` would compile an expression to bytecode at runtime, which a
 * GraalVM native image cannot do.
 */
class LogbackConfigTest :
    FunSpec({
        test("resolveLogbackConfigFile maps LISTENUP_LOG_FORMAT to a static config resource") {
            val cases: List<Pair<String?, String>> =
                listOf(
                    "json" to "logback-json.xml",
                    "JSON" to "logback-json.xml",
                    "Json" to "logback-json.xml",
                    null to "logback.xml",
                    "" to "logback.xml",
                    "text" to "logback.xml",
                    "anything" to "logback.xml",
                )
            cases.forEach { (logFormat, expectedFile) ->
                resolveLogbackConfigFile(logFormat) shouldBe expectedFile
            }
        }

        test("logback.xml is a valid config with a text pattern encoder") {
            val context = configureFrom("logback.xml")

            context.errorStatuses().shouldBeEmpty()
            context.stdoutEncoder().shouldBeInstanceOf<PatternLayoutEncoder>()
        }

        test("logback-json.xml is a valid config with a Logstash JSON encoder") {
            val context = configureFrom("logback-json.xml")

            context.errorStatuses().shouldBeEmpty()
            context.stdoutEncoder().shouldBeInstanceOf<LogstashEncoder>()
        }
    })

/** Loads [resource] from the classpath into a fresh [LoggerContext] via Joran. */
private fun configureFrom(resource: String): LoggerContext {
    val context = LoggerContext()
    val configurator = JoranConfigurator().apply { this.context = context }
    val stream =
        LogbackConfigTest::class.java.classLoader.getResourceAsStream(resource)
            ?: error("logback config resource not found on classpath: $resource")
    stream.use { configurator.doConfigure(it) }
    return context
}

private fun LoggerContext.errorStatuses(): List<Status> = statusManager.copyOfStatusList.filter { it.level == Status.ERROR }

private fun LoggerContext.stdoutEncoder(): Any {
    val root = getLogger(Logger.ROOT_LOGGER_NAME)
    val appender = root.getAppender("STDOUT").shouldNotBeNull()
    appender.shouldBeInstanceOf<ConsoleAppender<*>>()
    return appender.encoder
}
