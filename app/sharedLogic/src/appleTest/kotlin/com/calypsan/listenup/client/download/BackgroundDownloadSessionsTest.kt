package com.calypsan.listenup.client.download

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import platform.Foundation.NSURLRequestReloadIgnoringLocalCacheData

/**
 * The two background sessions downloads run in. "Download on Wi-Fi Only" lives on the session, not
 * the request: a task resumed from resume data replays its original request, so only the session's
 * configuration can still refuse a metered network for a half-fetched file.
 *
 * Configurations only — a background session per identifier is a process-wide resource, and the test
 * binary has no app bundle to own one.
 */
class BackgroundDownloadSessionsTest :
    FunSpec({
        val identifiers = DownloadSessionIdentifiers.forBundle("com.calypsan.listenup.debug")

        test("both identifiers come from the bundle, so a debug and a release build never share a session") {
            identifiers.anyNetwork shouldBe "com.calypsan.listenup.debug.downloads"
            identifiers.unmetered shouldBe "com.calypsan.listenup.debug.downloads.unmetered"
            identifiers.anyNetwork shouldNotBe identifiers.unmetered
        }

        test("an identifier names its network, and a stranger's names none") {
            identifiers.networkOf(identifiers.anyNetwork) shouldBe DownloadNetwork.Any
            identifiers.networkOf(identifiers.unmetered) shouldBe DownloadNetwork.Unmetered
            identifiers.networkOf("com.someone.else.downloads").shouldBeNull()
        }

        test("the Wi-Fi-only session refuses an expensive network, and the other allows one") {
            // Both asserted: true is the platform default, so only the pair catches an inverted boolean.
            backgroundDownloadConfiguration(identifiers, DownloadNetwork.Unmetered).allowsExpensiveNetworkAccess shouldBe false
            backgroundDownloadConfiguration(identifiers, DownloadNetwork.Any).allowsExpensiveNetworkAccess shouldBe true
        }

        test("each configuration is the background session for its identifier") {
            backgroundDownloadConfiguration(identifiers, DownloadNetwork.Unmetered).identifier shouldBe identifiers.unmetered
            backgroundDownloadConfiguration(identifiers, DownloadNetwork.Any).identifier shouldBe identifiers.anyNetwork
        }

        test("a finished transfer relaunches the app to be filed, and runs as soon as the person asked") {
            val configuration = backgroundDownloadConfiguration(identifiers, DownloadNetwork.Any)
            configuration.sessionSendsLaunchEvents shouldBe true
            configuration.discretionary shouldBe false
        }

        test("a download may take a week, three files at a time, and never from a cache") {
            val configuration = backgroundDownloadConfiguration(identifiers, DownloadNetwork.Unmetered)
            configuration.timeoutIntervalForResource shouldBe 604_800.0
            configuration.HTTPMaximumConnectionsPerHost shouldBe 3L
            configuration.requestCachePolicy shouldBe NSURLRequestReloadIgnoringLocalCacheData
        }

        test("the preference picks the session, read each time it is asked") {
            networkFor(wifiOnlyDownloads = true) shouldBe DownloadNetwork.Unmetered
            networkFor(wifiOnlyDownloads = false) shouldBe DownloadNetwork.Any
        }
    })
