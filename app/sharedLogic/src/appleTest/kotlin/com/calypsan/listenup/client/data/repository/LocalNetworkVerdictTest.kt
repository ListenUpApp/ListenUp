@file:OptIn(ExperimentalForeignApi::class)

package com.calypsan.listenup.client.data.repository

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Network.nw_connection_state_cancelled
import platform.Network.nw_connection_state_failed
import platform.Network.nw_connection_state_preparing
import platform.Network.nw_connection_state_ready
import platform.Network.nw_connection_state_waiting
import platform.Network.nw_path_unsatisfied_reason_local_network_denied
import platform.Network.nw_path_unsatisfied_reason_not_available

/**
 * The pure half of [AppleLocalNetworkAccess]'s TN3179 probe. The probe itself needs a physical
 * device — the simulator does not enforce Local Network privacy — so this pins the reading of the
 * connection's state, which is where a wrong answer would come from.
 */
class LocalNetworkVerdictTest :
    FunSpec({
        test("waiting because local network access was denied is the gate") {
            localNetworkVerdict(nw_connection_state_waiting, nw_path_unsatisfied_reason_local_network_denied) shouldBe true
        }

        test("waiting for any other reason is not the gate") {
            localNetworkVerdict(nw_connection_state_waiting, nw_path_unsatisfied_reason_not_available) shouldBe false
            localNetworkVerdict(nw_connection_state_waiting, null) shouldBe false
        }

        test("a connection that settles is not the gate") {
            localNetworkVerdict(nw_connection_state_ready, null) shouldBe false
            localNetworkVerdict(nw_connection_state_failed, null) shouldBe false
            localNetworkVerdict(nw_connection_state_cancelled, null) shouldBe false
        }

        test("a connection still being set up has no verdict yet") {
            localNetworkVerdict(nw_connection_state_preparing, null) shouldBe null
        }
    })
