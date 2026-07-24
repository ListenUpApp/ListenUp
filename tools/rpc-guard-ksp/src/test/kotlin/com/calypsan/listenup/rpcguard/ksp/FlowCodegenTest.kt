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
            // A synchronous construction throw must be caught by the same `.catch`: wrap the
            // delegate flow in `flow { emitAll(...) }` so `observe()` throwing while building the
            // flow (require/precondition/DI lookup) is deferred into the collected flow, not escaped.
            generated.shouldContain("flow { emitAll(delegate.observe()) }")
            generated.shouldContain(".catch { e ->")
            generated.shouldNotContain("delegate.observe().catch")
            // C2: every streaming service carries the session-liveness gate — the guard takes a
            // nullable liveness predicate and every stream is routed through `.gatedByLiveness(...)`, so a
            // revoked session's live stream is severed with a terminal RpcEvent.Error.
            generated.shouldContain("private val sessionLiveness: (suspend () -> Boolean)? = null,")
            generated.shouldContain(
                ".gatedByLiveness(sessionLiveness) " +
                    "{ com.calypsan.listenup.api.error.AuthError.SessionExpired() }",
            )
            // The gate is emitted file-private in the streaming guard itself (not shared from
            // :contract) so no server-infra symbol reaches the client export surface.
            generated.shouldContain("private fun <T> Flow<RpcEvent<T>>.gatedByLiveness(")
            generated.shouldContain("private const val STREAM_LIVENESS_POLL_MILLIS = 30000L")
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
