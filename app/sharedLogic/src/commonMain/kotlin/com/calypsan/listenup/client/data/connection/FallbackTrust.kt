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

    /** True when [candidateUrl] may replace [currentUrl] as the address credentials are sent to. */
    suspend fun allows(
        candidateUrl: String,
        currentUrl: String?,
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
            val cached = last?.takeIf { it.matches(candidateUrl, expected) }
            cached?.allowed
                ?: judge(candidateUrl, expected).also {
                    last = Verdict(url = candidateUrl, expected = expected, allowed = it, madeAt = timeSource.markNow())
                }
        }
    }

    private suspend fun judge(
        candidateUrl: String,
        expected: String?,
    ): Boolean =
        when (val result = probe(candidateUrl)) {
            is AppResult.Success -> {
                val same = expected == null || result.data.serverInfo.instanceId == expected
                if (!same) logger.warn { "Address ${candidateUrl.hostOfUrl()} answered as a different server" }
                same
            }

            is AppResult.Failure -> {
                false
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

    private class Verdict(
        private val url: String,
        private val expected: String?,
        val allowed: Boolean,
        private val madeAt: TimeMark,
    ) {
        fun matches(
            candidateUrl: String,
            expectedId: String?,
        ): Boolean = url == candidateUrl && expected == expectedId && madeAt.elapsedNow() < VERDICT_TTL
    }

    internal companion object {
        /** How long a verdict is reused, so a failing request storm does not probe on every call. */
        val VERDICT_TTL: Duration = 30.seconds
    }
}
