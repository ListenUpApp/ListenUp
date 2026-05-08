@file:OptIn(org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi::class)

package com.calypsan.listenup.rpcguard.ksp

import com.tschuchort.compiletesting.JvmCompilationResult
import com.tschuchort.compiletesting.KotlinCompilation
import com.tschuchort.compiletesting.SourceFile
import com.tschuchort.compiletesting.configureKsp
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class RpcGuardSymbolProcessorTest : FunSpec({

    test("processor runs cleanly on a source set with no @Rpc interfaces") {
        val result = compile(
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

private fun compile(vararg sources: SourceFile): JvmCompilationResult =
    KotlinCompilation().apply {
        this.sources = sources.toList()
        configureKsp {
            symbolProcessorProviders += RpcGuardSymbolProcessorProvider()
        }
        inheritClassPath = true
    }.compile()
