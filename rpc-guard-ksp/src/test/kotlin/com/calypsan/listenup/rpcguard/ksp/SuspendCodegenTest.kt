@file:OptIn(org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi::class)

package com.calypsan.listenup.rpcguard.ksp

import com.tschuchort.compiletesting.KotlinCompilation
import com.tschuchort.compiletesting.SourceFile
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

class SuspendCodegenTest :
    FunSpec({

        test("generates Guarded class for a suspend-only @Rpc service") {
            val result =
                compile(
                    SourceFile.kotlin(
                        "FakeContract.kt",
                        """
                        package fake

                        import kotlinx.rpc.annotations.Rpc

                        @Rpc
                        interface FakeService {
                            suspend fun foo(x: String): com.calypsan.listenup.api.result.AppResult<Int>
                        }
                        """.trimIndent(),
                    ),
                )
            result.exitCode shouldBe KotlinCompilation.ExitCode.OK
            val generated = result.kspGeneratedFile("FakeServiceGuarded.kt")
            generated.shouldContain("internal class FakeServiceGuarded(")
            generated.shouldContain("private val delegate: fake.FakeService")
            generated.shouldContain(
                "override suspend fun foo(x: kotlin.String): com.calypsan.listenup.api.result.AppResult<kotlin.Int>",
            )
            generated.shouldContain("catch (e: kotlinx.coroutines.CancellationException)")
            generated.shouldContain(
                "AppResult.Failure(\n            com.calypsan.listenup.api.error.InternalError(",
            )
            generated.shouldContain("cause = e::class.simpleName")
            generated.shouldContain("private val log: Logger = LoggerFactory.getLogger(\"rpc.FakeService\")")
            generated.shouldContain("private val metrics: RpcGuardMetrics = RpcGuardMetrics.global")
            generated.shouldContain("val cid = currentCorrelationId() ?: newCorrelationId()")
            generated.shouldContain(
                """withMdc("service" to "FakeService", "method" to "foo", "correlationId" to cid)""",
            )
            generated.shouldContain("log.error(\"Uncaught exception in FakeService.foo [cid=\$cid]\", e)")
            generated.shouldContain(
                """metrics.recordEscape("FakeService", "foo", e::class.simpleName ?: "Unknown")""",
            )
            generated.shouldContain("correlationId = cid")
        }
    })
