package com.calypsan.listenup.server.rpcguard

/**
 * Test-only stubs for the runtime helpers that production `:server` ships in
 * Tasks 8 and 9. The KSP-generated `<Service>Guarded` classes call these by
 * package-qualified name; at test compile time these stubs resolve, at
 * production compile time the real server helpers resolve.
 * Same package, different module — no ambiguity.
 *
 * Declarations are non-internal so kctfork's separate compilation unit can
 * see them via `inheritClassPath = true`.
 *
 * Behavior is intentionally trivial — the Task 5 codegen test asserts on the
 * source of the generated class, not on its runtime behavior. Real behavior
 * tests against `<Service>Guarded` live in `:server` (Tasks 11-13).
 */

suspend fun currentCorrelationId(): String? = null

fun newCorrelationId(): String = "test-correlation-id"

@Suppress("UnusedParameter")
suspend fun <R> withMdc(
    vararg pairs: Pair<String, String>,
    block: suspend () -> R,
): R = block()
