package com.calypsan.listenup.server.konsist

import com.lemonappdev.konsist.api.Konsist
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.ints.shouldBeGreaterThan

/**
 * Guard for issue #949: the native release binary is stripped (`-s`), erasing the symbol table that
 * kotlin-logging's `KotlinLogging.logger {}` reads to derive names — so every release log line
 * collapses to `[UnknownLogger]`. Every `:server` logger must use an explicit, strip-safe name:
 * `loggerFor<T>()` or `KotlinLogging.logger("<fully.qualified.name>")`. The empty-lambda form is
 * banned across all server production source sets, and string names must be fully-qualified so the
 * JVM `LISTENUP_LOG_LEVEL_<pkg>` overrides keep matching.
 */
class NoSymbolDerivedLoggerNamesRule :
    FunSpec({
        val serverProduction = Regex("/server/src/(commonMain|jvmMain|linuxMain)/")
        val emptyLambdaLogger = Regex("""KotlinLogging\.logger\s*(\(\s*)?\{""")
        val namedLogger = Regex("""KotlinLogging\.logger\s*\(\s*"([^"]+)"""")

        test("no server logger uses the symbol-derived KotlinLogging.logger {} form") {
            val serverFiles =
                Konsist
                    .scopeFromProduction()
                    .files
                    .filter { serverProduction.containsMatchIn(it.path) }

            // Guard against a vacuous pass: a future scope misconfiguration that matches no server
            // production files must fail loudly, not silently report zero offenders.
            serverFiles.size shouldBeGreaterThan 0

            val offenders =
                serverFiles
                    .filter { file -> emptyLambdaLogger.containsMatchIn(stripComments(file.text)) }
                    .map { it.path }

            offenders.shouldBeEmpty()
        }

        test("explicitly-named server loggers use a fully-qualified name") {
            val offenders =
                Konsist
                    .scopeFromProduction()
                    .files
                    .filter { serverProduction.containsMatchIn(it.path) }
                    .flatMap { file ->
                        val strippedText = stripComments(file.text)
                        namedLogger
                            .findAll(strippedText)
                            .map { file to it.groupValues[1] }
                            .toList()
                    }.filterNot { (_, name) -> name.startsWith("com.calypsan.listenup.server") }
                    .map { (file, name) -> "${file.path}: \"$name\"" }

            offenders.shouldBeEmpty()
        }
    })
