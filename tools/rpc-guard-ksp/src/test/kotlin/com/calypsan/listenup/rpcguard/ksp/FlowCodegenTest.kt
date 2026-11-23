@file:OptIn(org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi::class)

package com.calypsan.listenup.rpcguard.ksp

import com.calypsan.listenup.api.error.AuthError
import com.calypsan.listenup.api.error.InternalError
import com.calypsan.listenup.api.streaming.RpcEvent
import com.tschuchort.compiletesting.JvmCompilationResult
import com.tschuchort.compiletesting.KotlinCompilation
import com.tschuchort.compiletesting.SourceFile
import io.github.oshai.kotlinlogging.KotlinLogging
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

class FlowCodegenTest :
    FunSpec({

        test("generates Guarded class for a streaming @Rpc service") {
            val result = compileFakeStream()
            result.exitCode shouldBe KotlinCompilation.ExitCode.OK
            val generated = result.kspGeneratedFile("FakeStreamGuarded.kt")
            generated.shouldContain(
                "override fun observe(): kotlinx.coroutines.flow.Flow<com.calypsan.listenup.api.streaming.RpcEvent<kotlin.String>>",
            )
            // Every stream is routed through the file-private guardedStream, which opens the
            // delegate's flow lazily inside the guarded scope: a synchronous throw while BUILDING the
            // flow (require/precondition/DI lookup) is sanitized, not escaped.
            generated.shouldContain(
                """guardedStream("FakeStream", "observe", sessionLiveness, log) { delegate.observe() }""",
            )
            generated.shouldNotContain("delegate.observe().catch")
            generated.shouldContain("private val sessionLiveness: (suspend () -> Boolean)? = null,")
            // The guard is emitted file-private in the streaming guard itself (not shared from
            // :contract) so no server-infra symbol reaches the client export surface.
            generated.shouldContain("private fun <T> guardedStream(")
            generated.shouldContain("private const val STREAM_LIVENESS_POLL_MILLIS = 30000L")
            // Same correlation id + MDC scope an ordinary call gets.
            generated.shouldContain("val cid = currentCorrelationId() ?: newCorrelationId()")
            generated.shouldContain("""withMdc("service" to service, "method" to method, "correlationId" to cid)""")
            // The session is checked BEFORE the service's flow is opened.
            generated.shouldContain("if (isLive != null && !isLive()) {")
            generated.shouldContain("catch (e: CancellationException) {")
            generated.shouldNotContain("recordEscape")
            // The wire InternalError must NOT carry the server exception's class name or message —
            // those routinely embed SQL / paths / hostnames. The full detail stays in the server log.
            generated.shouldNotContain("e::class.simpleName")
            generated.shouldNotContain("e.message")
            generated.shouldContain("log.error(e) { \"Uncaught flow exception in \$service.\$method [cid=\$cid]\" }")
            generated.shouldNotContain("org.slf4j")
        }

        test("a session revoked before the stream opens gets SessionExpired at once and the service is never subscribed") {
            val guard = compileFakeStream().guardOf(FakeStreamBehaviour.HANG) { false }

            val events = guard.collectWithin()

            guard.subscriptions() shouldBe 0
            val error = events.single().shouldBeInstanceOf<RpcEvent.Error>().error
            error.shouldBeInstanceOf<AuthError.SessionExpired>()
            error.correlationId shouldBe "test-correlation-id"
        }

        test("a live session's stream passes every event through, then completes") {
            val guard = compileFakeStream().guardOf(FakeStreamBehaviour.EMIT_TWO) { true }

            guard.collectWithin() shouldBe listOf(RpcEvent.Data("a"), RpcEvent.Data("b"))
            guard.subscriptions() shouldBe 1
        }

        test("an unarmed guard streams untouched") {
            val guard = compileFakeStream().guardOf(FakeStreamBehaviour.EMIT_TWO, liveness = null)

            guard.collectWithin() shouldBe listOf(RpcEvent.Data("a"), RpcEvent.Data("b"))
        }

        test("a throw while building the service's flow ends the stream with a sanitized InternalError") {
            val guard = compileFakeStream().guardOf(FakeStreamBehaviour.THROW_ON_OPEN) { true }

            val error =
                guard
                    .collectWithin()
                    .single()
                    .shouldBeInstanceOf<RpcEvent.Error>()
                    .error
            error.shouldBeInstanceOf<InternalError>()
            error.correlationId shouldBe "test-correlation-id"
            error.debugInfo shouldBe null
        }
    })

private enum class FakeStreamBehaviour { HANG, EMIT_TWO, THROW_ON_OPEN }

private fun compileFakeStream(): JvmCompilationResult =
    compile(
        SourceFile.kotlin(
            "FakeStreamContract.kt",
            """
            package fake

            import com.calypsan.listenup.api.streaming.RpcEvent
            import kotlinx.coroutines.awaitCancellation
            import kotlinx.coroutines.flow.Flow
            import kotlinx.coroutines.flow.flow
            import kotlinx.rpc.annotations.Rpc

            @Rpc
            interface FakeStream {
                fun observe(): Flow<RpcEvent<String>>
            }

            class FakeStreamImpl(private val behaviour: String) : FakeStream {
                var subscriptions = 0

                override fun observe(): Flow<RpcEvent<String>> {
                    check(behaviour != "THROW_ON_OPEN") { "boom at /srv/secret/path" }
                    return flow {
                        subscriptions++
                        if (behaviour == "HANG") awaitCancellation()
                        emit(RpcEvent.Data("a"))
                        emit(RpcEvent.Data("b"))
                    }
                }
            }
            """.trimIndent(),
        ),
    )

/** The compiled `FakeStreamGuarded` wrapping a `FakeStreamImpl`, driven reflectively across kctfork's class loader. */
private class CompiledGuard(
    private val impl: Any,
    private val guard: Any,
) {
    @Suppress("UNCHECKED_CAST")
    fun observe(): Flow<RpcEvent<String>> = guard.javaClass.getMethod("observe").invoke(guard) as Flow<RpcEvent<String>>

    fun subscriptions(): Int = impl.javaClass.getMethod("getSubscriptions").invoke(impl) as Int

    // A short wall-clock bound: a refusal that waited for the ~30s poll would fail here, not pass late.
    fun collectWithin(): List<RpcEvent<String>> = runBlocking { withTimeout(5.seconds) { observe().toList() } }
}

private fun JvmCompilationResult.guardOf(
    behaviour: FakeStreamBehaviour,
    liveness: (suspend () -> Boolean)?,
): CompiledGuard {
    exitCode shouldBe KotlinCompilation.ExitCode.OK
    val impl = classLoader.loadClass("fake.FakeStreamImpl").getConstructor(String::class.java).newInstance(behaviour.name)
    val guardClass = classLoader.loadClass("com.calypsan.listenup.server.rpcguard.FakeStreamGuarded")
    val constructor = guardClass.constructors.single { it.parameterCount == 3 }
    val guard = constructor.newInstance(impl, liveness, KotlinLogging.logger("FlowCodegenTest"))
    return CompiledGuard(impl, guard)
}
