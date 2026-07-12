@file:OptIn(org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi::class)

package com.calypsan.listenup.rpcguard.ksp

import com.tschuchort.compiletesting.KotlinCompilation
import com.tschuchort.compiletesting.SourceFile
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain

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
            generated.shouldContain("class FakeServiceGuarded(")
            generated.shouldContain("private val delegate: fake.FakeService")
            generated.shouldContain(
                "override suspend fun foo(x: kotlin.String): com.calypsan.listenup.api.result.AppResult<kotlin.Int>",
            )
            generated.shouldContain("catch (e: kotlinx.coroutines.CancellationException)")
            generated.shouldContain(
                "AppResult.Failure(\n            com.calypsan.listenup.api.error.InternalError(",
            )
            // The wire InternalError must NOT carry the server exception's class name or message —
            // those routinely embed SQL / paths / hostnames. The full detail stays in the server log.
            generated.shouldNotContain("e::class.simpleName")
            generated.shouldNotContain("e.message")
            generated.shouldContain(
                "private val log: io.github.oshai.kotlinlogging.KLogger = " +
                    "io.github.oshai.kotlinlogging.KotlinLogging.logger(\"rpc.FakeService\")",
            )
            generated.shouldNotContain("org.slf4j")
            generated.shouldContain("val cid = currentCorrelationId() ?: newCorrelationId()")
            generated.shouldContain(
                """withMdc("service" to "FakeService", "method" to "foo", "correlationId" to cid)""",
            )
            generated.shouldContain("log.error(e) { \"Uncaught exception in FakeService.foo [cid=\$cid]\" }")
            generated.shouldContain("correlationId = cid")
            // Micrometer fully removed: the guard logs escapes but records no metric.
            generated.shouldNotContain("RpcGuardMetrics")
            generated.shouldNotContain("recordEscape")
        }

        test("preserves nullable type argument in AppResult<T?> return type") {
            val result =
                compile(
                    SourceFile.kotlin(
                        "FakeNullableContract.kt",
                        """
                        package fake

                        import kotlinx.rpc.annotations.Rpc

                        @Rpc
                        interface FakeNullableService {
                            suspend fun getOptional(id: String): com.calypsan.listenup.api.result.AppResult<Int?>
                        }
                        """.trimIndent(),
                    ),
                )
            result.exitCode shouldBe KotlinCompilation.ExitCode.OK
            val generated = result.kspGeneratedFile("FakeNullableServiceGuarded.kt")
            generated.shouldContain(
                "override suspend fun getOptional(id: kotlin.String): com.calypsan.listenup.api.result.AppResult<kotlin.Int?>",
            )
        }
    })
