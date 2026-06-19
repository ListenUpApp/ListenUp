package com.calypsan.listenup.server.mdns

import com.calypsan.listenup.api.sync.SyncControl
import com.calypsan.listenup.server.sync.ChangeBus
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/**
 * The regression guard for the rename-propagation bug: an admin server rename broadcasts
 * [SyncControl.ServerInfoChanged] on the [ChangeBus], and that nudge MUST drive an mDNS re-announce.
 * Before the fix there was no consumer wiring this nudge to the advertiser, so the LAN kept seeing the
 * old name until restart.
 */
class MdnsRefreshOnServerInfoChangeTest :
    FunSpec({
        test("a ServerInfoChanged broadcast re-announces mDNS") {
            runTest {
                val bus = ChangeBus()
                val advertiser = RecordingMdnsAdvertiser()
                backgroundScope.launchMdnsRefreshOnServerInfoChange(bus, advertiser)
                runCurrent() // let the collector subscribe (control frames are not replayed)

                bus.broadcastControl(SyncControl.ServerInfoChanged)
                runCurrent()

                advertiser.refreshCount shouldBe 1
            }
        }

        test("an unrelated control frame does not re-announce mDNS") {
            runTest {
                val bus = ChangeBus()
                val advertiser = RecordingMdnsAdvertiser()
                backgroundScope.launchMdnsRefreshOnServerInfoChange(bus, advertiser)
                runCurrent()

                bus.broadcastControl(SyncControl.ActivityChanged)
                runCurrent()

                advertiser.refreshCount shouldBe 0
            }
        }
    })

private class RecordingMdnsAdvertiser : MdnsAdvertiser {
    var refreshCount = 0
        private set

    override suspend fun start() = Unit

    override suspend fun stop() = Unit

    override suspend fun refresh() {
        refreshCount++
    }
}
