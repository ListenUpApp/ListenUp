package com.calypsan.listenup.server.konsist

import com.lemonappdev.konsist.api.Konsist
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual

/**
 * Konsist guard pinning the Foundation-Trio invariant that
 * [com.calypsan.listenup.server.librarywrite.LibraryWriteBroker] is the **sole component that
 * writes inside library folders**. The packages that handle library-folder content (`scanner`
 * today; `organize` and `upload` when those phases land — the prefix list already names them so
 * they are born covered) must contain no direct filesystem-write token; every mutation routes
 * through the broker, which suppresses the watcher, journals multi-op manifests, and degrades
 * typed on unwritable roots.
 *
 * Token-based like [SidecarParsersAreReadOnly]: comments are stripped so a mention in KDoc can't
 * false-fail. The floor assertions keep the rule non-vacuous — it must observe both the broker
 * package (else the invariant's subject is gone) and a real population of checked files.
 */
class LibraryWritesGoThroughBrokerRule :
    FunSpec({

        /** Package prefixes whose code handles library-folder content and must never write directly. */
        val libraryContentPackages =
            listOf(
                "com.calypsan.listenup.server.scanner",
                "com.calypsan.listenup.server.organize",
                "com.calypsan.listenup.server.upload",
            )

        /** The kotlinx-io write surfaces (and the repo's helpers over them) a checked package must not touch. */
        val forbiddenWriteTokens =
            listOf(
                "SystemFileSystem.sink(",
                "SystemFileSystem.atomicMove(",
                "SystemFileSystem.delete(",
                "SystemFileSystem.createDirectories(",
                ".writeText(",
                ".writeBytes(",
                "deleteRecursively(",
                "createTempFileIn(",
            )

        // Add a path suffix here ONLY with a review note proving the write never lands inside a
        // library folder. This list is a debt ledger, not an escape hatch.
        val allowlist =
            listOf(
                // CoverSpool stages embedded-cover bytes under $LISTENUP_HOME/scan-spool — the
                // server data home, never a library folder (see its class KDoc).
                "server/scanner/CoverSpool.kt",
            )

        test("library-content packages have no direct FS-write tokens — the broker is the sole library writer") {
            val serverCommonMainFiles =
                Konsist
                    .scopeFromProduction()
                    .files
                    .filter { it.path.contains("server/src/commonMain") }

            // Non-vacuous floor 1: the broker package must exist — if librarywrite disappears,
            // the invariant this rule pins has no subject and the rule must fail, not pass.
            val brokerFiles =
                serverCommonMainFiles.filter {
                    it.packagee?.name == "com.calypsan.listenup.server.librarywrite"
                }
            brokerFiles.size shouldBeGreaterThanOrEqual 4

            val checkedFiles =
                serverCommonMainFiles.filter { file ->
                    val pkg = file.packagee?.name ?: return@filter false
                    libraryContentPackages.any { pkg == it || pkg.startsWith("$it.") }
                }
            // Non-vacuous floor 2: the scanner package alone is far larger than this.
            checkedFiles.size shouldBeGreaterThanOrEqual 10

            val offenders =
                checkedFiles
                    .filterNot { file -> allowlist.any { file.path.endsWith(it) } }
                    .flatMap { file ->
                        val body = stripComments(file.text)
                        forbiddenWriteTokens
                            .filter { token -> body.contains(token) }
                            .map { token -> "${file.path} -> $token" }
                    }

            offenders.shouldBeEmpty()
        }
    })
