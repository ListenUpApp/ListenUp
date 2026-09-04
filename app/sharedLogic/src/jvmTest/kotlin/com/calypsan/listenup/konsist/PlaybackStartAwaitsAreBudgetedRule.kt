package com.calypsan.listenup.konsist

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty

/**
 * Every RPC await on the tap-to-audio path carries an explicit latency budget.
 *
 * Three separate incidents in one week were the same bug: an await between the play tap and the
 * first audio sample that rode `RpcChannel`'s 15s `DEFAULT_RPC_TIMEOUT` instead of a budget sized
 * for a person waiting with a phone in their hand.
 *
 * - `PlaybackPrepareRepositoryImpl.getPosition` — 15s, and `idempotent = true` bought a second
 *   15s retry. **30s** of silence on a fully-downloaded book.
 * - `CachedAudioTokenProvider.prepareForPlayback` — awaited a token refresh unconditionally, as
 *   step 1, before it knew whether the book was even streaming. **8.08s of a 9.17s tap-to-audio**,
 *   measured on device, to fetch a token a downloaded book never uses.
 * - `PlaybackPreparer.fetchBookFromServer` — the first bug verbatim, at a second call site, still
 *   unfixed months later.
 *
 * Each was found by reading logs after a listener hit it. Each fix was correct and each left the
 * next unbudgeted await in place, because nothing made the omission visible. Prose didn't hold the
 * invariant — `fetchAuthoritativePosition`'s KDoc claimed it "never blocks", which is precisely
 * where a 30s stall hid in plain sight. This rule holds it instead.
 *
 * **The rule:** in the playback-start packages, every `channel.call(...)` / `rpcChannel.call(...)`
 * passes an explicit `timeout = `, and none passes `idempotent = true`. The retry leg is banned
 * here rather than merely discouraged because it silently *doubles* whatever budget is declared —
 * the mechanism that turned 15s into 30s twice.
 *
 * **Scope is deliberately narrow.** Only code reachable while a listener is staring at a play
 * button that hasn't responded yet. Background sync, catch-up and admin paths keep the 15s default;
 * nobody is waiting on those, and widening this rule would earn it a reputation for crying wolf.
 *
 * Sibling rule [NoThrowsInDataLayerRule] pins the result contract on the same layer; this one pins
 * the latency contract.
 */
class PlaybackStartAwaitsAreBudgetedRule :
    FunSpec({

        /**
         * Files on the path between the tap and the first audio sample. A call added here without a
         * budget is a call a listener waits on in silence.
         */
        val playbackStartPaths =
            listOf(
                "/client/playback/PlaybackPreparer.kt",
                "/client/playback/PlaybackManagerImpl.kt",
                "/client/playback/CachedAudioTokenProvider.kt",
                "/client/data/repository/PlaybackPrepareRepositoryImpl.kt",
                "/client/data/repository/PlaybackPositionRepositoryImpl.kt",
            )

        fun onPlaybackStartPath(path: String) = playbackStartPaths.any { path.endsWith(it) }

        // `channel.call(` / `rpcChannel.call(` / `it.call(` — the RpcChannel await, however the
        // receiver is named at the call site.
        val rpcCall = Regex("""\b\w*[Cc]hannel\.call\s*\(""")

        /**
         * The function's CODE, with comment lines stripped.
         *
         * `KoFunctionDeclaration.text` includes the KDoc, and these functions' KDocs quote the very
         * anti-patterns this rule bans while explaining the incidents that produced them —
         * `getPosition`'s doc says *"It ran on the 15s channel default with `idempotent = true`"*.
         * Matching raw text flagged that honest history as a live offence. Prose describing a fixed
         * bug must not read as the bug; [NoThrowsInDataLayerRule] strips comments for exactly the
         * same reason.
         */
        fun codeOf(text: String): String =
            text
                .lineSequence()
                .map { it.trim() }
                .filterNot { it.startsWith("//") || it.startsWith("*") || it.startsWith("/*") }
                .joinToString("\n")

        test("RPC awaits on the playback-start path declare an explicit timeout") {
            val offenders =
                productionScope()
                    .functions()
                    .filter { onPlaybackStartPath(it.path) }
                    .filter { fn ->
                        val body = codeOf(fn.text)
                        rpcCall.containsMatchIn(body) && !body.contains("timeout =")
                    }.map { "${it.name} in ${it.path}" }

            offenders.shouldBeEmpty()
        }

        test("RPC awaits on the playback-start path never opt into the idempotent retry") {
            // `idempotent = true` licenses one automatic retry re-applying the SAME timeout, so a
            // declared budget silently becomes double. That is exactly how getPosition's 15s became
            // 30s, and fetchBookFromServer's after it.
            val offenders =
                productionScope()
                    .functions()
                    .filter { onPlaybackStartPath(it.path) }
                    .filter { fn ->
                        val body = codeOf(fn.text)
                        rpcCall.containsMatchIn(body) && body.contains("idempotent = true")
                    }.map { "${it.name} in ${it.path}" }

            offenders.shouldBeEmpty()
        }
    })
