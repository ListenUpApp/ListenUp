@file:OptIn(org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi::class)

package com.calypsan.listenup.rpcguard.ksp

import com.tschuchort.compiletesting.KotlinCompilation
import com.tschuchort.compiletesting.SourceFile
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

class DispatcherCodegenTest :
    FunSpec({

        test("dispatcher emits one overload per discovered service") {
            val result =
                compile(
                    SourceFile.kotlin(
                        "Contracts.kt",
                        """
                        package fake

                        import kotlinx.coroutines.flow.Flow
                        import kotlinx.rpc.annotations.Rpc

                        @Rpc
                        interface Alpha { suspend fun a(): com.calypsan.listenup.api.result.AppResult<Int> }

                        @Rpc
                        interface Beta { fun b(): Flow<com.calypsan.listenup.api.streaming.RpcEvent<String>> }
                        """.trimIndent(),
                    ),
                )
            result.exitCode shouldBe KotlinCompilation.ExitCode.OK
            val dispatcher = result.kspGeneratedFile("RpcGuardDispatcher.kt")
            // Every service's overload threads the session-liveness probe, suspend-only included.
            dispatcher.shouldContain(
                "fun guard(impl: fake.Alpha, sessionLiveness: (suspend () -> Boolean)? = null): fake.Alpha = " +
                    "AlphaGuarded(impl, sessionLiveness)",
            )
            dispatcher.shouldContain(
                "fun guard(impl: fake.Beta, sessionLiveness: (suspend () -> Boolean)? = null): fake.Beta = " +
                    "BetaGuarded(impl, sessionLiveness)",
            )
        }
    })
