@file:OptIn(org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi::class)

package com.calypsan.listenup.rpcguard.ksp

import com.tschuchort.compiletesting.KotlinCompilation
import com.tschuchort.compiletesting.SourceFile
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain

class FlowCodegenTest :
    FunSpec({

        test("generates Guarded class for a streaming @Rpc service") {
            val result =
                compile(
                    SourceFile.kotlin(
                        "FakeStreamContract.kt",
                        """
                        package fake

                        import kotlinx.rpc.annotations.Rpc
                        import kotlinx.coroutines.flow.Flow

                        @Rpc
                        interface FakeStream {
                            fun observe(): Flow<com.calypsan.listenup.api.streaming.RpcEvent<String>>
                        }
                        """.trimIndent(),
                    ),
                )
            result.exitCode shouldBe KotlinCompilation.ExitCode.OK
            val generated = result.kspGeneratedFile("FakeStreamGuarded.kt")
            generated.shouldContain(
                "override fun observe(): kotlinx.coroutines.flow.Flow<com.calypsan.listenup.api.streaming.RpcEvent<kotlin.String>>",
            )
            generated.shouldContain("delegate.observe().catch { e ->")
            generated.shouldContain("if (e is kotlinx.coroutines.CancellationException) throw e")
            generated.shouldContain(
                "emit(\n            com.calypsan.listenup.api.streaming.RpcEvent.Error(",
            )
            generated.shouldNotContain("recordEscape")
            // The wire InternalError must NOT carry the server exception's class name or message —
            // those routinely embed SQL / paths / hostnames. The full detail stays in the server log.
            generated.shouldNotContain("e::class.simpleName")
            generated.shouldNotContain("e.message")
            generated.shouldContain("log.error(e) { \"Uncaught flow exception in FakeStream.observe [cid=\$cid]\" }")
            generated.shouldNotContain("org.slf4j")
        }
    })
