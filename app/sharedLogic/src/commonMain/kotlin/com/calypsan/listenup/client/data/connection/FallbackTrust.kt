package com.calypsan.listenup.client.data.connection

import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.data.repository.hostOfUrl
import com.calypsan.listenup.client.data.repository.isLocalNetworkHost
import com.calypsan.listenup.client.domain.repository.VerifiedServer
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource

private val logger = KotlinLogging.logger {}

/**
 * Decides whether a second address for the server may take over from the current one.
 *
 * The remote address is learned from an unauthenticated probe, so it is untrusted until the moment
 * of use. An address passes when the server answering there reports the instance id this client is
 * connected to, and taking it is not a step down from `https` to plain `http` on a public host.
 *
 * This is identification, not authentication: the instance id is public. It rules out an address
 * that points at some other machine; it does not rule out a machine built to impersonate this one.
 *
 * Verdicts are reused so a burst of failing requests shares one probe, but for different lengths:
 * an answer from the server (the same one, or another one) is a fact about the address and is reused
 * for [VERDICT_TTL]; no answer at all is a fact about the network at that instant and is reused only
 * for [UNREACHABLE_TTL], and not at all when the caller asks [allows] with `fresh`.
 *
 * @param probe asks the server at a URL who it is (an unauthenticated `getServerInfo`).
 * @param connectedServerId the instance id this client is connected to; null when none was recorded,
 *   in which case any reachable candidate passes so older installs keep roaming.
 */
internal class FallbackTrust(
    private val probe: suspend (url: String) -> AppResult<VerifiedServer>,
    private val connectedServerId: suspend () -> String?,
    private val timeSource: TimeSource = TimeSource.Monotonic,
) {
    private val mutex = Mutex()
    private var last: Verdict? = null

    /**
     * True when [candidateUrl] may replace [currentUrl] as the address credentials are sent to.
     *
     * @param fresh the caller is reacting to a network change, so a remembered "no answer" is stale
     *   by definition and is ignored. A remembered same-server or different-server answer is a fact
     *   about the address and is still reused.
     */
    suspend fun allows(
        candidateUrl: String,
        currentUrl: String?,
        fresh: Boolean = false,
    ): Boolean {
        // The active-URL type accepts only absolute http(s) URLs, so anything else could never be used.
        if (!candidateUrl.hasHttpScheme()) return false
        if (isDowngrade(candidateUrl, currentUrl)) {
            logger.info { "Refusing a step down to plain http at ${candidateUrl.hostOfUrl()}" }
            return false
        }
        val expected = connectedServerId()
        // The probe runs under the lock so concurrent failing requests share one probe.
        return mutex.withLock {
            val cached = last?.takeIf { it.matches(candidateUrl, expected, fresh) }
            cached?.allowed
                ?: judge(candidateUrl, expected).let { outcome ->
                    last =
                        Verdict(
                            url = candidateUrl,
                            expected = expected,
                            outcome = outcome,
                            madeAt = timeSource.markNow(),
                        )
                    outcome == Outcome.SAME_SERVER
                }
        }
    }

    private suspend fun judge(
        candidateUrl: String,
        expected: String?,
    ): Outcome =
        when (val result = probe(candidateUrl)) {
            is AppResult.Success -> {
                if (expected == null || result.data.serverInfo.instanceId == expected) {
                    Outcome.SAME_SERVER
                } else {
                    logger.warn { "Address ${candidateUrl.hostOfUrl()} answered as a different server" }
                    Outcome.DIFFERENT_SERVER
                }
            }

            is AppResult.Failure -> {
                Outcome.NO_ANSWER
            }
        }

    private fun isDowngrade(
        candidateUrl: String,
        currentUrl: String?,
    ): Boolean =
        currentUrl != null &&
            currentUrl.startsWith("https://") &&
            candidateUrl.startsWith("http://") &&
            !isLocalNetworkHost(candidateUrl.hostOfUrl())

    private fun String.hasHttpScheme(): Boolean = startsWith("http://") || startsWith("https://")

    private enum class Outcome { SAME_SERVER, DIFFERENT_SERVER, NO_ANSWER }

    private class Verdict(
        private val url: String,
        private val expected: String?,
        private val outcome: Outcome,
        private val madeAt: TimeMark,
    ) {
        val allowed: Boolean get() = outcome == Outcome.SAME_SERVER

        fun matches(
            candidateUrl: String,
            expectedId: String?,
            fresh: Boolean,
        ): Boolean {
            if (url != candidateUrl || expected != expectedId) return false
            if (outcome == Outcome.NO_ANSWER) return !fresh && madeAt.elapsedNow() < UNREACHABLE_TTL
            return madeAt.elapsedNow() < VERDICT_TTL
        }
    }

    internal companion object {
        /**
         * How long a same-server or different-server answer is reused. Both are facts about the
         * address, so a burst of failing requests shares one probe.
         */
        val VERDICT_TTL: Duration = 30.seconds

        /**
         * How long a "no answer" is reused. It is a fact about the network at that instant, not
         * about the server: long enough that a burst of failing requests shares one probe, short
         * enough that a network change is noticed.
         */
        val UNREACHABLE_TTL: Duration = 3.seconds
    }
}
