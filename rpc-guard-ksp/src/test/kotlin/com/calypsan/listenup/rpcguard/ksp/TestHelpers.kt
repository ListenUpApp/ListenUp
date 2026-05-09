@file:OptIn(ExperimentalCompilerApi::class)

package com.calypsan.listenup.rpcguard.ksp

import com.tschuchort.compiletesting.JvmCompilationResult
import com.tschuchort.compiletesting.KotlinCompilation
import com.tschuchort.compiletesting.SourceFile
import com.tschuchort.compiletesting.configureKsp
import com.tschuchort.compiletesting.sourcesGeneratedBySymbolProcessor
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

/**
 * Returns the text of a KSP-generated source file with the given [name].
 *
 * Uses [sourcesGeneratedBySymbolProcessor] from kctfork 0.12.1, which walks
 * the KSP output directory on the compilation result without requiring the
 * caller to stash a reference to the [KotlinCompilation] instance.
 *
 * @throws NoSuchElementException if no file with that name was generated.
 */
internal fun JvmCompilationResult.kspGeneratedFile(name: String): String =
    sourcesGeneratedBySymbolProcessor.first { it.name == name }.readText()
