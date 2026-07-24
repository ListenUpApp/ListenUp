@file:OptIn(org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi::class)

package com.calypsan.listenup.rpcguard.ksp

import com.tschuchort.compiletesting.KotlinCompilation
import com.tschuchort.compiletesting.SourceFile
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * Scaffold smoke test — proves the module builds and the [RpcGuardSymbolProcessorProvider]
 * wires through ServiceLoader. Real coverage of discovery, return-shape validation, and
 * codegen lands in Tasks 4–7's tests.
 */
class RpcGuardSymbolProcessorTest :
    FunSpec({

        test("processor runs cleanly on a source set with no @Rpc interfaces") {
            val result =
                compile(
                    SourceFile.kotlin(
                        "Empty.kt",
                        """
                        class Empty
                        """.trimIndent(),
                    ),
                )
            result.exitCode shouldBe KotlinCompilation.ExitCode.OK
        }
    })
