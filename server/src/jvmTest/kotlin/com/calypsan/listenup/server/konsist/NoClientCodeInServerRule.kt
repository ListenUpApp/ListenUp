package com.calypsan.listenup.server.konsist

import com.lemonappdev.konsist.api.Konsist
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldNotBeEmpty

/**
 * Konsist guard pinning the post-decomposition module boundary: `:server` depends on
 * `:contract` only and must never reach into the client application layer. The cross-stack
 * contract (`com.calypsan.listenup.api.*`), the shared foundation (`com.calypsan.listenup.core.*`),
 * and the embedded-metadata parser (`com.calypsan.listenup.domain.embeddedmeta.*`) all live in
 * `:contract`. The entire `com.calypsan.listenup.client.*` tree is client-only application code
 * (Room DAOs, ViewModels, playback, download, sync). If a `:server` source file imports a
 * `client.*` symbol, either the symbol belongs in `:contract` or the server is doing something
 * it should not — this rule fails the build either way.
 *
 * **Covers every server source set, not just `jvmMain`.** The filter was written when `:server`
 * was JVM-only; the Kotlin/Native port then moved the bulk of the server into `commonMain`, and
 * the rule kept auditing the leftovers — **37 files of ~507**, i.e. ~7% of the module, with the
 * 441 commonMain files (the overwhelmingly likely place for a stray `client.*` import) invisible
 * to it. `NoSymbolDerivedLoggerNamesRule` already spans all three sets; this now matches it.
 */
private val SERVER_PRODUCTION = Regex("/server/src/(commonMain|jvmMain|linuxMain)/")

class NoClientCodeInServerRule :
    FunSpec({
        test("server production code never imports client application packages") {
            val serverFiles =
                Konsist
                    .scopeFromProduction()
                    .files
                    .filter { SERVER_PRODUCTION.containsMatchIn(it.path) }

            // Vacuity guard: a source-set rename would otherwise leave this matching nothing and
            // passing green — exactly how the jvmMain-only filter quietly stopped covering the
            // server after the Kotlin/Native port.
            serverFiles.shouldNotBeEmpty()

            val offenders =
                serverFiles.flatMap { file ->
                    file.imports
                        .filter { it.name.startsWith("com.calypsan.listenup.client.") }
                        .map { "${file.path} -> ${it.name}" }
                }

            offenders.shouldBeEmpty()
        }
    })
