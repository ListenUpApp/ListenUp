package com.calypsan.listenup.konsist

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.io.File
import java.nio.file.Files

/**
 * Guards the module-discovery half of [productionScope].
 *
 * Discovery walking the wrong depth is a silent failure: every architectural rule still
 * runs and still reports green, over a fraction of the code it was written to police.
 * These tests make that failure loud. The first pins the traversal shape against a
 * synthetic tree; the last pins the real repo, so a future layout change that orphans
 * a module fails here instead of quietly narrowing the gate.
 */
class KonsistScopeTest :
    FunSpec({

        test("discovers modules at the repo root and one level inside a grouping directory") {
            val root = Files.createTempDirectory("konsist-scope").toFile()
            listOf(
                "contract/src",
                "server/src",
                "app/sharedLogic/src",
                "app/sharedUI/src",
                "tools/rpc-guard-ksp/src",
            ).forEach { File(root, it).mkdirs() }

            discoverModuleSrcDirs(root) shouldBe
                listOf(
                    "app/sharedLogic/src",
                    "app/sharedUI/src",
                    "contract/src",
                    "server/src",
                    "tools/rpc-guard-ksp/src",
                )
        }

        test("skips hidden directories so worktree copies are never parsed") {
            val root = Files.createTempDirectory("konsist-scope-hidden").toFile()
            listOf(
                "contract/src",
                ".worktrees/feature-branch/contract/src",
                ".claude/worktrees/agent-abc/contract/src",
            ).forEach { File(root, it).mkdirs() }

            discoverModuleSrcDirs(root) shouldBe listOf("contract/src")
        }

        test("skips build output directories at both levels") {
            val root = Files.createTempDirectory("konsist-scope-build").toFile()
            listOf(
                "contract/src",
                "build/generated/thing/src",
                "app/build/generated/thing/src",
                "app/sharedLogic/src",
            ).forEach { File(root, it).mkdirs() }

            discoverModuleSrcDirs(root) shouldBe listOf("app/sharedLogic/src", "contract/src")
        }

        test("discovery of the real repo matches the canonical module list exactly") {
            val discovered = discoverModuleSrcDirs(File(com.lemonappdev.konsist.api.Konsist.projectRootPath))

            // Exact match, not containment: a module that appears, disappears, or moves must fail
            // here. Containment would let the gate silently narrow — the failure this test exists
            // to catch. If this fails after a deliberate module change, update EXPECTED_MODULE_DIRS.
            discovered shouldBe EXPECTED_MODULE_SRC_DIRS
        }

        test("the parsed production scope actually contains the codebase") {
            // The other cases here guard DIRECTORY discovery. This one guards PARSING: if
            // scopeFromDirectories ever comes back near-empty (a Konsist upgrade changing the
            // creator's semantics, a source-set layout change), every rule in this package would
            // still report green over nothing — and the ban rules, whose population IS this scope,
            // have no other guard at all.
            //
            // Collapse floors, not ratchets — 2,001 files / 2,243 classes / 472 interfaces were
            // parsed on 2026-09-09, so an ordinary deletion never comes near these bars.
            val scope = productionScope()
            assertScopeNotEmpty(scope.files, expectedMin = 800, why = "the production Kotlin file set")
            assertScopeNotEmpty(scope.classes(), expectedMin = 800, why = "the production class set")
            assertScopeNotEmpty(scope.interfaces(), expectedMin = 100, why = "the production interface set")
        }
    })
