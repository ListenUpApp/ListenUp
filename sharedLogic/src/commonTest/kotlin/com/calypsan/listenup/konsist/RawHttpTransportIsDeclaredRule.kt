package com.calypsan.listenup.konsist

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe

/**
 * The capstone of RPC-by-default: any data-layer class that reaches for the raw Ktor client instead
 * of dispatching through the [com.calypsan.listenup.client.data.remote.RpcChannel] seam is EITHER
 * fixed RPC infrastructure (the plumbing that *builds* the channel) OR a **declared, reviewed**
 * exception carrying a `@NonRpcTransport(reason)` annotation. Anything else fails the build.
 *
 * The whole point of the seam is that "reach past the RPC engine" is a compile error, not a review
 * comment — every robustness capability (bounded timeout, 401-heal, single-flight reconnect,
 * outcome-unknown typing) reaches every service for free. Raw HTTP forgoes all of that, so it must
 * never be the *silent* default. This rule makes the mandate physical: "if something's not on RPC and
 * we can't justify why, we move it over." A new repository that quietly opens `clientFactory
 * .getClient()` fails here until someone either routes it through the channel or tags it with the
 * bucket it legitimately falls in.
 *
 * **The tell.** A call to one of the two [com.calypsan.listenup.client.data.remote.ApiClientFactory]
 * client accessors — `getClient()` (authed request client) or
 * `getUnauthenticatedStreamingClient()` (pre-auth SSE). Every raw-HTTP surface in the data layer reaches the
 * network through exactly one of these. Matching on the accessor call (file text) rather than an
 * import is deliberate: the factory and its same-package siblings (`ImageApi`, `RpcProxyCache`, …)
 * reference it with no import statement, so an import-based tell would silently miss them. Mirrors the
 * text-based tell in [CoroutineScopeInstallsExceptionHandlerRule].
 *
 * **The allowlist** is the RPC/HTTP plumbing itself — the classes that *are* the seam, so tagging
 * them "not RPC" would be nonsense:
 *  - `ApiClientFactory.kt` — defines the accessors; the one shared client cache.
 *  - `RpcProxyCache.kt` — the bounded, self-healing engine every channel rides; derives its RPC
 *    client from `apiClientFactory.getClient()`.
 *  - `InstanceRpcFactory.kt` — the deliberately-cacheless pre-auth probe (it builds its own RPC proxy
 *    and only *names* `getClient()` in its KDoc; already pinned by
 *    [RawProxyConstructionIsChannelOnlyRule]).
 *
 * Sibling infra that references the factory but calls no accessor — `RemoteCache`, `RpcAuthRecovery`
 * (calls `invalidateRequestClientOnly()`), `RpcCacheInvalidator` — never matches the tell, so it needs
 * no allowlist entry.
 *
 * **Heuristic limit.** The scope is `data/remote/` + `data/repository/` only. A raw-HTTP surface
 * that takes an injected `HttpClient` lambda rather than the factory (e.g. `SyncCatchUpClient` in
 * `data/sync/`) does not match — this rule guards the factory-accessor surface, not every possible
 * byte that leaves over HTTP (the raw SSE reader itself is pinned by
 * [RawSseConstructionIsChannelOnlyRule]).
 */
class RawHttpTransportIsDeclaredRule :
    FunSpec({
        test("raw-HTTP data-layer classes are RPC infrastructure or carry @NonRpcTransport") {
            val allowlist = setOf("ApiClientFactory.kt", "RpcProxyCache.kt", "InstanceRpcFactory.kt")

            // A call to any ApiClientFactory client accessor — the one door to the raw network in
            // the data layer.
            val accessorCalls =
                listOf("getClient()", "getUnauthenticatedStreamingClient()")

            val rawHttpFiles =
                productionScope()
                    .files
                    .filter { it.path.contains("/data/remote/") || it.path.contains("/data/repository/") }
                    .filter { file -> accessorCalls.any { call -> file.text.contains(call) } }

            // The rule must be REACHABLE — a rule that silently matches nothing stops guarding.
            rawHttpFiles.shouldNotBeEmpty()

            val offenders =
                rawHttpFiles
                    .filterNot { file -> allowlist.any { allowed -> file.path.endsWith("/$allowed") } }
                    .filterNot { file -> file.text.contains("@NonRpcTransport") }
                    .map { it.path }

            offenders shouldBe emptyList()
        }
    })
