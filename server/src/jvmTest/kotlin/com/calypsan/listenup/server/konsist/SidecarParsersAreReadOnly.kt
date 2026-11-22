package com.calypsan.listenup.server.konsist

import com.lemonappdev.konsist.api.Konsist
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual

/**
 * Konsist guard pinning the project invariant that metadata sidecars are
 * **read-only enrichment inputs** — a `SidecarParser` reads a `.nfo`, `.opf`,
 * `reader.txt` or `desc.txt` into [com.calypsan.listenup.server.scanner.sidecar.SidecarMetadata]
 * and MUST NOT write to disk. The database is the sole source of truth; the
 * scanner never writes back to the library tree.
 *
 * The rule finds every concrete class implementing the `SidecarParser`
 * interface in `:server` production code and asserts that none of their
 * function bodies contain a disk-write call. The parsers live in commonMain, so
 * the forbidden tokens are the kotlinx-io write surfaces and the library-write
 * broker — JVM `java.nio` calls could not compile there. Line and block
 * comments are stripped before matching so a `// Files.write(...)` note can't
 * false-fail.
 *
 * The implementation count is asserted non-empty so the rule cannot pass
 * vacuously: it must observe the five real parsers (`NfoParser`, `OpfParser`,
 * `ReaderTxtParser`, `DescTxtParser`, `ListenUpSidecarReader`).
 */
class SidecarParsersAreReadOnly :
    FunSpec({

        /**
         * The write surfaces a commonMain parser could actually reach: the kotlinx-io calls and the repo's
         * io helpers over them (the same tokens as [LibraryWritesGoThroughBrokerRule]), plus the broker
         * itself — that rule allows a broker write, but a parser must not write at all.
         */
        val forbiddenWriteTokens =
            listOf(
                "SystemFileSystem.sink(",
                "SystemFileSystem.atomicMove(",
                "SystemFileSystem.delete(",
                "SystemFileSystem.createDirectories(",
                ".writeText(",
                ".writeBytes(",
                "deleteRecursively(",
                "deleteEntry(",
                "createTempFileIn(",
                "LibraryWriteBroker",
                "WriteOp.",
            )

        test("SidecarParser implementations never write to disk") {
            val implementations =
                Konsist
                    // Module-scoped, not whole-repo: `scopeFromProduction()` parses every module's
                    // PSI before any filter runs, and whichever spec lands first in a cold forked
                    // worker pays that full parse inside its own Kotest 120s timeout — fork
                    // batching (forkEvery) reshuffles who pays whenever test classes are added.
                    // Scoping to :server keeps the parse bounded regardless of batch order (same
                    // rationale as CollectionMembershipRoutingRule). The implementation-count
                    // assertion below keeps the narrowing honest: if the scope is ever too tight,
                    // the rule fails loudly rather than passing on nothing.
                    .scopeFromProduction("server")
                    .classes()
                    .filter { cls ->
                        cls.parents().any { it.name == "SidecarParser" }
                    }

            // Guard against a vacuous pass: the five real parsers must be present.
            implementations.size shouldBeGreaterThanOrEqual 5

            val offenders =
                implementations.flatMap { cls ->
                    cls.functions().flatMap { fn ->
                        val body = stripComments(fn.text)
                        forbiddenWriteTokens
                            .filter { token -> body.contains(token) }
                            .map { token -> "${cls.name}.${fn.name} -> $token @ ${fn.path}" }
                    }
                }

            offenders.shouldBeEmpty()
        }
    })
