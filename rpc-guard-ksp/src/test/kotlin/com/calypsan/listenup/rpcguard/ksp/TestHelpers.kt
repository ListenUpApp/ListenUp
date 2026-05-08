@file:OptIn(ExperimentalCompilerApi::class)

package com.calypsan.listenup.rpcguard.ksp

import com.tschuchort.compiletesting.JvmCompilationResult
import com.tschuchort.compiletesting.KotlinCompilation
import com.tschuchort.compiletesting.SourceFile
import com.tschuchort.compiletesting.configureKsp
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi

/**
 * Compiles [sources] in-process with the RPC-guard KSP processor attached.
 *
 * Includes `-Xskip-prerelease-check` because `:shared` (a runtime test dep)
 * is built with Kotlin 2.3 pre-release class metadata and kctfork's embedded
 * compiler rejects it without the flag.
 *
 * TODO: drop `-Xskip-prerelease-check` once `:shared` builds against a
 * stable Kotlin release.
 */
internal fun compile(vararg sources: SourceFile): JvmCompilationResult =
    KotlinCompilation()
        .apply {
            this.sources = sources.toList()
            configureKsp {
                symbolProcessorProviders += RpcGuardSymbolProcessorProvider()
            }
            inheritClassPath = true
            // :shared is compiled with Kotlin 2.3 pre-release; kctfork's embedded compiler
            // rejects pre-release class files by default.
            kotlincArguments = listOf("-Xskip-prerelease-check")
        }.compile()
