package com.calypsan.listenup.client.download

import platform.Foundation.NSBundle
import platform.Foundation.NSURLRequestReloadIgnoringLocalCacheData
import platform.Foundation.NSURLSession
import platform.Foundation.NSURLSessionConfiguration
import platform.Foundation.NSURLSessionDelegateProtocol

/**
 * Whole-resource timeout for one download, in seconds: seven days, NSURLSession's own default and
 * the lifetime of the download URLs the server mints. Time spent waiting for Wi-Fi counts against it.
 */
private const val RESOURCE_TIMEOUT_SECONDS = 604_800.0

/**
 * How many files download at once.
 *
 * ListenUp servers are self-hosted — very often a single-board computer on a home LAN — so an
 * unbounded fan-out is a self-inflicted denial of service on the user's own hardware. Three keeps a
 * fat pipe busy while leaving headroom for the streaming player on the same link. Every file's task
 * is created when the download starts, and the session holds all but three back: creating the next
 * task only when one finishes would mean creating tasks from the background, which iOS delays more
 * with every wake.
 */
internal const val MAX_CONCURRENT_FILE_DOWNLOADS = 3L

/** Which networks a background session may use. */
internal enum class DownloadNetwork {
    /** Any network, metered or not. */
    Any,

    /** Unmetered only: what "Download on Wi-Fi Only" asks for. */
    Unmetered,
}

/** The session the Wi-Fi-only preference selects. Read each time it is asked, so a toggle takes effect. */
internal fun networkFor(wifiOnlyDownloads: Boolean): DownloadNetwork =
    if (wifiOnlyDownloads) DownloadNetwork.Unmetered else DownloadNetwork.Any

/**
 * The identifiers of the two background download sessions. They come from the bundle identifier, so
 * a debug and a release build on one phone never share a session, and they never change: the system
 * hands a relaunched app its transfers by identifier.
 */
internal data class DownloadSessionIdentifiers(
    val anyNetwork: String,
    val unmetered: String,
) {
    fun of(network: DownloadNetwork): String =
        when (network) {
            DownloadNetwork.Any -> anyNetwork
            DownloadNetwork.Unmetered -> unmetered
        }

    /** The network [identifier]'s session uses, or null when it is not one of ours. */
    fun networkOf(identifier: String?): DownloadNetwork? =
        when (identifier) {
            anyNetwork -> DownloadNetwork.Any
            unmetered -> DownloadNetwork.Unmetered
            else -> null
        }

    companion object {
        fun forBundle(bundleIdentifier: String): DownloadSessionIdentifiers =
            DownloadSessionIdentifiers(
                anyNetwork = "$bundleIdentifier.downloads",
                unmetered = "$bundleIdentifier.downloads.unmetered",
            )

        /** The identifiers for the running app. */
        fun current(): DownloadSessionIdentifiers =
            forBundle(NSBundle.mainBundle.bundleIdentifier ?: "com.calypsan.listenup")
    }
}

/**
 * The configuration of the background session for [network].
 *
 * "Download on Wi-Fi Only" is set here, on the session, and not only on each request: a task resumed
 * from resume data replays the request it started with, so a request flag set then cannot be changed
 * for a half-fetched file. Two sessions, one per preference, and a toggle moves the transfers between
 * them. Background sessions always wait for a network they may use, which is the pause the setting
 * promises; `waitsForConnectivity` is ignored by them and is not set.
 *
 * Not discretionary: the person tapped Download, and tasks are created while the app is in front,
 * where the flag is honoured.
 */
internal fun backgroundDownloadConfiguration(
    identifiers: DownloadSessionIdentifiers,
    network: DownloadNetwork,
): NSURLSessionConfiguration =
    NSURLSessionConfiguration.backgroundSessionConfigurationWithIdentifier(identifiers.of(network)).apply {
        sessionSendsLaunchEvents = true
        discretionary = false
        timeoutIntervalForResource = RESOURCE_TIMEOUT_SECONDS
        HTTPMaximumConnectionsPerHost = MAX_CONCURRENT_FILE_DOWNLOADS
        requestCachePolicy = NSURLRequestReloadIgnoringLocalCacheData
        allowsExpensiveNetworkAccess = network == DownloadNetwork.Any
    }

/**
 * The two background download sessions, created once per process.
 *
 * Creating a background session with a known identifier is what hands a relaunched app the
 * transfers the system kept running while it was gone, so both are created eagerly, at launch, before
 * the system delivers their events. [delegate] is held strongly by each session until it is
 * invalidated, which never happens while the process lives.
 */
internal class BackgroundDownloadSessions(
    val identifiers: DownloadSessionIdentifiers,
    delegate: NSURLSessionDelegateProtocol,
) {
    private val sessions: Map<DownloadNetwork, NSURLSession> =
        DownloadNetwork.entries.associateWith { network ->
            NSURLSession.sessionWithConfiguration(
                configuration = backgroundDownloadConfiguration(identifiers, network),
                delegate = delegate,
                delegateQueue = null,
            )
        }

    fun session(network: DownloadNetwork): NSURLSession = sessions.getValue(network)

    val all: Map<DownloadNetwork, NSURLSession> get() = sessions
}
