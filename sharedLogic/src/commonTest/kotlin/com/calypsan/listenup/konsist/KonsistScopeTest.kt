package com.calypsan.listenup.konsist

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainAll
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

        test("skips build output directories") {
            val root = Files.createTempDirectory("konsist-scope-build").toFile()
            listOf("contract/src", "build/generated/thing/src").forEach { File(root, it).mkdirs() }

            discoverModuleSrcDirs(root) shouldBe listOf("contract/src")
        }

        test("the real production scope covers every module that holds production code") {
            val discovered = discoverModuleSrcDirs(File(com.lemonappdev.konsist.api.Konsist.projectRootPath))

            discovered shouldContainAll
                listOf("contract/src", "server/src", "sharedLogic/src", "sharedUI/src")
        }
    })
