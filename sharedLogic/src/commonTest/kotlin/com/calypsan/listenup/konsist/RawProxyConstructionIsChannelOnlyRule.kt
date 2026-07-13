package com.calypsan.listenup.konsist

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe

/**
 * Raw kotlinx.rpc proxy construction — `client.rpc(url).withService<S>()` — lives in exactly two
 * places, and nowhere else:
 *
 *  - [com.calypsan.listenup.client.data.remote.RpcChannel]'s `rpcChannel<S>` connect lambda: the one
 *    production door through which every post-login service proxy is built, wrapped in the bounded,
 *    single-flight, self-healing engine.
 *  - [com.calypsan.listenup.client.data.remote.InstanceRpcFactory]: the allowlisted, deliberately
 *    cacheless pre-auth probe that verifies a server URL before any [ServerConfig] exists.
 *
 * The tell is the `kotlinx.rpc.withService` import: reaching a bare proxy is impossible without it.
 * Pinning the importing set to those two files makes "call the service proxy directly" a build
 * failure, not a review comment — the physical no-bypass the [RpcChannel] KDoc promises. A NEW
 * `withService` site anywhere else (a hand-rolled factory, a repository reaching past the channel)
 * fails here immediately.
 *
 * Note: [com.calypsan.listenup.client.data.remote.RpcProxyCache] mentions the connect lambda in its
 * KDoc but does not *import* `withService` (its callers supply the lambda), so it is correctly not an
 * offender — matching on the import, not raw text, sidesteps that false positive.
 */
class RawProxyConstructionIsChannelOnlyRule :
    FunSpec({
        test("raw withService proxy construction lives only in RpcChannel + InstanceRpcFactory") {
            val allowlist = setOf("RpcChannel.kt", "InstanceRpcFactory.kt")

            val rawProxyFiles =
                productionScope()
                    .files
                    .filter { file -> file.imports.any { it.name == "kotlinx.rpc.withService" } }

            // The seam must be REACHABLE — a rule that silently matches nothing stops guarding.
            rawProxyFiles.shouldNotBeEmpty()

            val offenders =
                rawProxyFiles
                    .filterNot { file -> allowlist.any { allowed -> file.path.endsWith("/$allowed") } }
                    .map { it.path }

            offenders shouldBe emptyList()
        }
    })
