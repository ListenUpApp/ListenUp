@file:OptIn(org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi::class)

package com.calypsan.listenup.rpcguard.ksp

import com.tschuchort.compiletesting.KotlinCompilation
import com.tschuchort.compiletesting.SourceFile
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

// Note: the processor emits the discovery line via KSPLogger.warn (not info) because
// kctfork 0.12.1's TestKSPLogger only forwards ERROR and EXCEPTION to the MessageCollector
// during KSP execution; info messages are held in recordedEvents and never reach
// result.messages. warn() goes through the same reportAll() path and is visible in
// result.messages, making it testable without inspecting generated files.

class DiscoveryTest :
    FunSpec({

        test("processor logs discovered service names for happy-path interfaces") {
            val result =
                compile(
                    SourceFile.kotlin(
                        "FakeContract.kt",
                        """
                        package fake

                        import kotlinx.coroutines.flow.Flow
                        import kotlinx.rpc.annotations.Rpc

                        @Rpc
                        interface FakeService {
                            suspend fun foo(x: String): com.calypsan.listenup.api.result.AppResult<Int>
                            fun observe(): Flow<com.calypsan.listenup.api.streaming.RpcEvent<String>>
                        }
                        """.trimIndent(),
                    ),
                )
            result.exitCode shouldBe KotlinCompilation.ExitCode.OK
            result.messages shouldContain "[rpc-guard] discovered: fake.FakeService [foo, observe]"
        }

        test("processor errors on a suspend method whose return type isn't AppResult<*>") {
            val result =
                compile(
                    SourceFile.kotlin(
                        "BadContract.kt",
                        """
                        package bad

                        import kotlinx.rpc.annotations.Rpc

                        @Rpc
                        interface BadService {
                            suspend fun foo(): String  // wrong: not AppResult<*>
                        }
                        """.trimIndent(),
                    ),
                )
            result.exitCode shouldBe KotlinCompilation.ExitCode.COMPILATION_ERROR
            result.messages shouldContain "BadService.foo: suspend functions on @Rpc interfaces must return AppResult<*>"
        }

        test("processor errors on a non-suspend method whose return isn't Flow<RpcEvent<*>>") {
            val result =
                compile(
                    SourceFile.kotlin(
                        "BadStream.kt",
                        """
                        package bad

                        import kotlinx.coroutines.flow.Flow
                        import kotlinx.rpc.annotations.Rpc

                        @Rpc
                        interface BadStream {
                            fun observe(): Flow<String>  // wrong: not Flow<RpcEvent<*>>
                        }
                        """.trimIndent(),
                    ),
                )
            result.exitCode shouldBe KotlinCompilation.ExitCode.COMPILATION_ERROR
            result.messages shouldContain "BadStream.observe: non-suspend functions on @Rpc interfaces must return Flow<RpcEvent<*>>"
        }
    })
