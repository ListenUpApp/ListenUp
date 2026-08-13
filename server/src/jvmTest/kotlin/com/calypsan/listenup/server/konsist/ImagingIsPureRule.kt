package com.calypsan.listenup.server.konsist

import com.lemonappdev.konsist.api.Konsist
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual

/**
 * Konsist guard pinning `server.imaging` as a **pure codec package**: bytes in, pixels out, bytes
 * out. No filesystem, no database, no Ktor, no server configuration, no `ImageStore`.
 *
 * This rule is the reason the package can leave. Canon's standing rule is doctrine-as-pattern rather
 * than a shared library *until two products demand extraction* — so the pipeline lives in `:server`
 * today, and what makes it genuinely reusable is not its address but its dependencies. Without this
 * rule, "we'll extract it one day" is a comment that rots the first time someone reaches for
 * `CoverImageStore` from inside a decoder. With it, extraction stays a move rather than an
 * excavation.
 *
 * **`server.compression` is allowed on purpose.** PNG decoding is inflate plus unfiltering, and that
 * inflate is ours already. It is itself a pure codec package held to this same standard, so
 * depending on it does not widen the surface the way depending on the rest of `:server` would.
 *
 * The file-count floor keeps the rule honest: if the package is ever renamed or the path filter
 * drifts, this fails loudly rather than passing over nothing.
 */
class ImagingIsPureRule :
    FunSpec({

        /** Package prefixes `server.imaging` is allowed to reach for inside our own codebase. */
        val allowedInternalPrefixes =
            listOf(
                "com.calypsan.listenup.server.imaging.",
                "com.calypsan.listenup.server.compression.",
            )

        /**
         * Impurity by any other route: transport, filesystem, and the platform image APIs a JVM
         * shortcut would reach for — which would also not compile on Kotlin/Native.
         */
        val forbiddenPrefixes =
            listOf(
                "io.ktor.",
                "kotlinx.io.files.",
                "java.io.",
                "java.nio.file.",
                "java.awt.",
                "javax.imageio.",
            )

        test("server.imaging depends on nothing but itself and our own compression codec") {
            // Selected by PACKAGE, not by path. Konsist also scans sibling git worktrees, so a
            // path filter of "/imaging/" matched every file in a checkout that merely happened to
            // live under `.worktrees/imaging/` — the whole repo, reported as offenders. The package
            // name is the property we actually mean, and it cannot be spoofed by a directory name.
            // The `/server/src/` narrowing stays for cost: it runs before any per-file import walk.
            val imagingFiles =
                Konsist
                    .scopeFromProduction()
                    .files
                    .filter { "/server/src/" in it.path }
                    .filter { it.packagee?.name == IMAGING_PACKAGE }

            // Guard against a vacuous pass — the package exists and has real files in it.
            imagingFiles.size shouldBeGreaterThanOrEqual 2

            val offenders =
                imagingFiles.flatMap { file ->
                    file.imports
                        .filter { import ->
                            val name = import.name
                            val reachesIntoServer =
                                name.startsWith("com.calypsan.listenup.server.") &&
                                    allowedInternalPrefixes.none { name.startsWith(it) }
                            reachesIntoServer || forbiddenPrefixes.any { name.startsWith(it) }
                        }.map { "${file.path} -> ${it.name}" }
                }

            offenders.shouldBeEmpty()
        }
    })

private const val IMAGING_PACKAGE = "com.calypsan.listenup.server.imaging"
