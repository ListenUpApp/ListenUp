package com.calypsan.listenup.konsist

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe

/**
 * Hand-rolled SSE line-reading — a raw `bodyAsChannel()` + `readLine()` parse loop — lives in exactly
 * one place: the shared [com.calypsan.listenup.client.data.sync.SseConnection] engine.
 *
 * The tell is a file importing BOTH `io.ktor.utils.io.readLine` AND
 * `io.ktor.client.statement.bodyAsChannel`: that pair is the fingerprint of a bespoke SSE transport
 * (matching on `prepareGet` alone is too broad — file downloads use it too). Before the Phase-A
 * extraction there were three such surfaces — the sync firehose plus the two pre-auth registration
 * streams — each with its own connect loop, its own (missing) connect bound, and its own fragile
 * parser. They now all compose over [SseConnection], which owns the one battle-tested loop: the 5s
 * Darwin connect bound, exponential reconnect, and spec-tolerant framing.
 *
 * Pinning the importing set to just `SseConnection.kt` makes "hand-roll another SSE reader" a build
 * failure, not a review comment — a new surface that reaches for `readLine` + `bodyAsChannel` instead
 * of [SseConnection] fails here immediately, dragging back the iOS silent-hang / lost-reconnect /
 * mandatory-space-parse bugs the extraction fixed. Mirrors [RawProxyConstructionIsChannelOnlyRule].
 */
class RawSseConstructionIsChannelOnlyRule :
    FunSpec({
        test("raw SSE channel line-reading lives only in SseConnection") {
            val allowlist = setOf("SseConnection.kt")

            val rawSseFiles =
                productionScope()
                    .files
                    .filter { file ->
                        file.imports.any { it.name == "io.ktor.utils.io.readLine" } &&
                            file.imports.any { it.name == "io.ktor.client.statement.bodyAsChannel" }
                    }

            // The rule must be REACHABLE — a rule that silently matches nothing stops guarding.
            rawSseFiles.shouldNotBeEmpty()

            val offenders =
                rawSseFiles
                    .filterNot { file -> allowlist.any { allowed -> file.path.endsWith("/$allowed") } }
                    .map { it.path }

            offenders shouldBe emptyList()
        }
    })
