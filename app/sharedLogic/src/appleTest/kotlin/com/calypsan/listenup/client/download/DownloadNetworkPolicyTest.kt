package com.calypsan.listenup.client.download

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import platform.Foundation.NSMutableURLRequest
import platform.Foundation.NSURL

/**
 * The "Download on Wi-Fi Only" preference, as it reaches NSURLSession.
 *
 * The preference shipped honoured on Android (WorkManager `NetworkType.UNMETERED`) and entirely
 * ignored on iOS: `wifiOnlyDownloads` had zero references anywhere in `appleMain`, so the toggle
 * was decorative and downloads ran over cellular whatever it said. That costs people money, which
 * is why these assertions are about the request object rather than about a mock.
 *
 * **Expensive, not cellular.** Android gates on *metering*, and [AppleNetworkMonitor] already
 * defines unmetered as `!nw_path_is_expensive(path)`. `allowsExpensiveNetworkAccess` is therefore
 * the matching knob; `allowsCellularAccess` would happily burn a metered personal hotspot that both
 * Android and this app's own network monitor already call expensive.
 */
class DownloadNetworkPolicyTest :
    FunSpec({

        fun request(): NSMutableURLRequest = NSMutableURLRequest.requestWithURL(NSURL.URLWithString("https://example.test/a.m4b")!!)

        test("wifi-only downloads refuse an expensive network") {
            val request = request()
            request.applyDownloadNetworkPolicy(wifiOnlyDownloads = true)
            request.allowsExpensiveNetworkAccess shouldBe false
        }

        // NOTE: `true` is the platform default, so this one cannot fail against a no-op
        // implementation — it is here to catch an INVERTED boolean, which is the likelier slip.
        test("with the preference off, an expensive network is allowed") {
            val request = request()
            request.applyDownloadNetworkPolicy(wifiOnlyDownloads = false)
            request.allowsExpensiveNetworkAccess shouldBe true
        }

        test("the policy is re-read per request, so toggling it mid-session takes effect") {
            // The session's configuration is the authority for a task resumed from resume data,
            // which replays its original request (BackgroundDownloadSessionsTest); the request carries
            // the policy too, so a new task never depends on that alone.
            //
            // Deliberately off-then-ON: the reverse order would land on `true`, the platform
            // default, and pass against an implementation that does nothing at all.
            val request = request()
            request.applyDownloadNetworkPolicy(wifiOnlyDownloads = false)
            request.applyDownloadNetworkPolicy(wifiOnlyDownloads = true)
            request.allowsExpensiveNetworkAccess shouldBe false
        }
    })
