package com.calypsan.listenup.konsist

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty

/**
 * The RPC-factory connection lifecycle (Mutex caching, invalidation, URL/scheme
 * resolution, kRPC client derivation) lives in exactly one place: [RpcProxyCache].
 * A hand-rolled Ktor*RpcFactory body would reintroduce the 22-file copy-paste this
 * generalization removed. KtorInstanceRpcFactory is allowlisted: it is a transient
 * pre-auth probe with timeout semantics, deliberately cacheless.
 *
 * As of W8a the last real post-login factory ([RpcProxyCache]-delegating `KtorPlaybackRpcFactory`)
 * is retired in favour of `rpcChannel<PlaybackService>()`, so the non-allowlisted factory set is now
 * empty — the pattern is gone, not merely tamed. This rule stays live as a **reintroduction ratchet**:
 * the moment anyone adds a new `Ktor*RpcFactory` that owns connection state of its own, it fails.
 * Raw-proxy construction itself is pinned separately by `RawProxyConstructionIsChannelOnlyRule`.
 */
class RpcFactoriesDelegateToRpcProxyCacheRule :
    FunSpec({
        test("Ktor RPC factories own no connection state of their own") {
            val allowlist = setOf("KtorInstanceRpcFactory")
            val factories =
                productionScope()
                    .classes()
                    .filter { it.name.startsWith("Ktor") && it.name.endsWith("RpcFactory") }
                    .filterNot { it.name in allowlist }

            val offenders =
                factories
                    .filter { cls ->
                        // Mutex fields are declared `private val mutex = Mutex()` with no
                        // explicit type annotation, so Konsist can't resolve `.type` on them —
                        // match the initializer text instead (same idiom as
                        // CoroutineScopeInstallsExceptionHandlerRule).
                        cls.properties().any { it.text.contains("Mutex(") } ||
                            cls.functions().any { it.name == "rpcClient" || it.name == "rpcBaseUrl" }
                    }.map { it.name }
            offenders.shouldBeEmpty()
        }
    })
