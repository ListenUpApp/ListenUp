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
 */
internal fun compile(vararg sources: SourceFile): JvmCompilationResult =
    KotlinCompilation()
        .apply {
            this.sources = sources.toList()
            configureKsp {
                symbolProcessorProviders += RpcGuardSymbolProcessorProvider()
            }
            inheritClassPath = true
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
