package com.calypsan.listenup.client.features.home

import com.calypsan.listenup.client.domain.model.ScanProgressState
import com.calypsan.listenup.client.presentation.home.HomeConnectivity
import com.calypsan.listenup.client.presentation.home.HomeSlot
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/** Home's offline and arrival-strip signals, which feed the shared block order. */
class HomeStateSignalsTest :
    FunSpec({
        test("the state's signals: offline is any connectivity but online; the strip is arriving or scanning") {
            homeReady(HomeSlot.Absent, emptyList()).isOffline shouldBe false
            homeReady(HomeSlot.Absent, emptyList(), connectivity = HomeConnectivity.ServerUnreachable).isOffline shouldBe true
            homeReady(HomeSlot.Absent, emptyList(), connectivity = HomeConnectivity.Offline).isOffline shouldBe true
            homeReady(HomeSlot.Absent, emptyList()).showsArrivalStrip shouldBe false
            homeReady(HomeSlot.Absent, emptyList(), isLibraryArriving = true).showsArrivalStrip shouldBe true
            homeReady(HomeSlot.Absent, emptyList(), scanProgress = scan()).showsArrivalStrip shouldBe true
        }
    })

private fun scan() = ScanProgressState(phase = "analyzing", current = 1, total = 2, added = 0, updated = 0, removed = 0)
