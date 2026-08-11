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
 * function bodies contain a disk-write call. The forbidden tokens cover the
 * common JVM disk-write surfaces a parser could reach for. Line and block
 * comments are stripped before matching so a `// Files.write(...)` note can't
 * false-fail.
 *
 * The implementation count is asserted non-empty so the rule cannot pass
 * vacuously: it must observe the four real parsers (`NfoParser`, `OpfParser`,
 * `ReaderTxtParser`, `DescTxtParser`).
 */
class SidecarParsersAreReadOnly :
    FunSpec({

        /** JVM disk-write surfaces a sidecar parser must never touch. */
        val forbiddenWriteTokens =
            listOf(
                "Files.write",
                "Files.newOutputStream",
                "Files.newBufferedWriter",
                "Files.createFile",
                "Files.createDirectory",
                "Files.copy",
                "Files.move",
                "Files.delete",
                ".writeText",
                ".writeBytes",
                ".appendText",
                ".appendBytes",
                ".outputStream(",
                ".bufferedWriter(",
                ".printWriter(",
                "FileOutputStream",
                "FileWriter",
                "PrintWriter",
                "RandomAccessFile",
            )

        test("SidecarParser implementations never write to disk") {
            val implementations =
                Konsist
                    .scopeFromProduction()
                    .classes()
                    // Narrow BEFORE resolving parents. `parents()` is the expensive call — it walks
                    // the supertype graph per class — and running it over every class in scope made
                    // this spec exceed Kotest's 120s timeout on any checkout carrying sibling git
                    // worktrees, whose sources Konsist also scans. Restricting to this module's
                    // production sources first leaves a handful of classes to resolve. The
                    // implementation-count assertion below is what keeps this narrowing honest: if
                    // the filter is ever too tight, the rule fails loudly rather than passing on
                    // nothing.
                    .filter { it.path.contains("/server/src/") }
                    .filter { cls ->
                        cls.parents().any { it.name == "SidecarParser" }
                    }

            // Guard against a vacuous pass: the four real parsers must be present.
            implementations.size shouldBeGreaterThanOrEqual 4

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
