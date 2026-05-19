package com.calypsan.listenup.server.konsist

import com.lemonappdev.konsist.api.Konsist
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty

/**
 * Konsist guard pinning the post-decomposition module boundary: `:server` depends on
 * `:contract` only and must never reach into the client application layer. The cross-stack
 * contract (`com.calypsan.listenup.api.*`), the shared foundation (`com.calypsan.listenup.core.*`),
 * and the embedded-metadata parser (`com.calypsan.listenup.domain.embeddedmeta.*`) all live in
 * `:contract`. The entire `com.calypsan.listenup.client.*` tree is client-only application code
 * (Room DAOs, ViewModels, playback, download, sync). If a `:server` source file imports a
 * `client.*` symbol, either the symbol belongs in `:contract` or the server is doing something
 * it should not — this rule fails the build either way.
 */
class NoClientCodeInServerRule :
    FunSpec({
        test("server production code never imports client application packages") {
            val offenders =
                Konsist
                    .scopeFromProduction()
                    .files
                    .filter { it.path.contains("/server/src/main/") }
                    .flatMap { file ->
                        file.imports
                            .filter { it.name.startsWith("com.calypsan.listenup.client.") }
                            .map { "${file.path} -> ${it.name}" }
                    }

            offenders.shouldBeEmpty()
        }
    })
