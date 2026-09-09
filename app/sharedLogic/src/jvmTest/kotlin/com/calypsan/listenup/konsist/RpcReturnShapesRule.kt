package com.calypsan.listenup.konsist

import com.calypsan.listenup.api.result.AppResult
import com.lemonappdev.konsist.api.KoModifier
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty

/**
 * Konsist guard pinning the return-shape contract for `@Rpc` service interfaces.
 *
 * The rule matches every function declared in an interface annotated `@Rpc` and
 * asserts:
 *
 * - **Suspend methods** must return `AppResult<*>` — failures are values, not
 *   thrown exceptions. The typed [com.calypsan.listenup.api.error.AppError]
 *   payload survives both REST and RPC transports because it's in-band data.
 * - **Non-suspend methods** must return `Flow<RpcEvent<*>>` — server-pushed
 *   streams are typed through [com.calypsan.listenup.api.streaming.RpcEvent] so
 *   any transport-level error is also a value, not an opaque channel close.
 *
 * This rule is a **backstop** for the KSP processor's compile-time errors.
 * KSP errors fire first; Konsist catches anything that slips past (e.g. when
 * KSP is not applied to a particular module, or during a partial refactor before
 * the build is re-run).
 *
 * Production `@Rpc` interfaces validated by this rule:
 * - `AuthServicePublic` — all suspend, all `AppResult<*>`
 * - `AuthServiceAuthed` — all suspend, all `AppResult<*>`
 * - `PingService` — single suspend `ping()`, returns `AppResult<String>`
 * - `ScannerService` — two suspend `AppResult<*>` methods, one non-suspend
 *   `observeProgress()` returning `Flow<RpcEvent<ScanEvent>>`
 */
class RpcReturnShapesRule :
    FunSpec({
        test("@Rpc interface suspend methods return AppResult<*>") {
            val rpcInterfaces = productionScope().interfaces().filter { i -> i.annotations.any { it.name == "Rpc" } }

            assertScopeNotEmpty(
                rpcInterfaces,
                expectedMin = 15,
                why = "@Rpc service interfaces — the contract surface this backstop checks behind KSP",
            )

            val offenders =
                rpcInterfaces
                    .flatMap { it.functions() }
                    .filter { fn -> fn.hasModifier(KoModifier.SUSPEND) }
                    .filter { fn ->
                        val rt = fn.returnType?.sourceType ?: return@filter true
                        !rt.startsWith("AppResult<")
                    }.map { fn ->
                        "${fn.name} @ ${fn.path}"
                    }

            offenders.shouldBeEmpty()
        }

        test("@Rpc interface non-suspend methods return Flow<RpcEvent<*>>") {
            val rpcInterfaces = productionScope().interfaces().filter { i -> i.annotations.any { it.name == "Rpc" } }

            // Guarded on the INTERFACE set, not the non-suspend function set: a service legitimately
            // declaring no streaming method is normal, so only the interface set collapsing is a bug.
            assertScopeNotEmpty(
                rpcInterfaces,
                expectedMin = 15,
                why = "@Rpc service interfaces — the contract surface this backstop checks behind KSP",
            )

            val offenders =
                rpcInterfaces
                    .flatMap { it.functions() }
                    .filter { fn -> !fn.hasModifier(KoModifier.SUSPEND) }
                    .filter { fn ->
                        val rt = fn.returnType?.sourceType ?: return@filter true
                        !rt.startsWith("Flow<RpcEvent<")
                    }.map { fn ->
                        "${fn.name} @ ${fn.path}"
                    }

            offenders.shouldBeEmpty()
        }
    })
