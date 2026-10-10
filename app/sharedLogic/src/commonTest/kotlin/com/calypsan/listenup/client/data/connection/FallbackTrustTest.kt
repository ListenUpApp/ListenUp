package com.calypsan.listenup.client.data.connection

import com.calypsan.listenup.api.dto.ServerInfo
import com.calypsan.listenup.api.dto.auth.RegistrationPolicy
import com.calypsan.listenup.api.error.TransportError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.domain.repository.VerifiedServer
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TestTimeSource

private fun serverWithInstance(instanceId: String): AppResult<VerifiedServer> =
    AppResult.Success(
        VerifiedServer(
            serverInfo =
                ServerInfo(
                    name = "ListenUp",
                    version = "1.0.0",
                    apiVersion = "v1",
                    setupRequired = false,
                    registrationPolicy = RegistrationPolicy.CLOSED,
                    instanceId = instanceId,
                ),
            verifiedUrl = "https://wan.example.com",
        ),
    )

private class FakeProbe(
    var answer: AppResult<VerifiedServer>,
) {
    val probed = mutableListOf<String>()
    val calls: Int get() = probed.size

    fun probe(url: String): AppResult<VerifiedServer> {
        probed += url
        return answer
    }
}

class FallbackTrustTest :
    FunSpec({
        val wan = "https://wan.example.com"

        test("the same instance answering at the candidate is allowed") {
            val probe = FakeProbe(serverWithInstance("A"))
            val trust = FallbackTrust(probe::probe, { "A" })

            trust.allows(wan, null) shouldBe true
        }

        test("a different instance answering at the candidate is refused") {
            val probe = FakeProbe(serverWithInstance("B"))
            val trust = FallbackTrust(probe::probe, { "A" })

            trust.allows(wan, null) shouldBe false
        }

        test("an unreachable candidate is refused") {
            val probe = FakeProbe(AppResult.Failure(TransportError.NetworkUnavailable()))
            val trust = FallbackTrust(probe::probe, { "A" })

            trust.allows(wan, null) shouldBe false
        }

        test("with no connected server id recorded, a reachable candidate is allowed") {
            val probe = FakeProbe(serverWithInstance("anything"))
            val trust = FallbackTrust(probe::probe, { null })

            trust.allows(wan, null) shouldBe true
        }

        test("with no connected server id recorded, an unreachable candidate is still refused") {
            val probe = FakeProbe(AppResult.Failure(TransportError.NetworkUnavailable()))
            val trust = FallbackTrust(probe::probe, { null })

            trust.allows(wan, null) shouldBe false
        }

        test("https to plain http on a public host is refused without probing") {
            val probe = FakeProbe(serverWithInstance("A"))
            val trust = FallbackTrust(probe::probe, { "A" })

            trust.allows("http://wan.example.com", "https://lan.example") shouldBe false
            probe.calls shouldBe 0
        }

        test("https to plain http on a private address is allowed") {
            val probe = FakeProbe(serverWithInstance("A"))
            val trust = FallbackTrust(probe::probe, { "A" })

            trust.allows("http://192.168.1.20:8080", "https://nas.example") shouldBe true
        }

        test("http to http on a public host is allowed") {
            val probe = FakeProbe(serverWithInstance("A"))
            val trust = FallbackTrust(probe::probe, { "A" })

            trust.allows("http://wan.example.com", "http://192.168.1.20:8080") shouldBe true
        }

        test("http to https is allowed") {
            val probe = FakeProbe(serverWithInstance("A"))
            val trust = FallbackTrust(probe::probe, { "A" })

            trust.allows("https://wan.example.com", "http://192.168.1.20:8080") shouldBe true
        }

        test("a null current URL applies no scheme rule") {
            val probe = FakeProbe(serverWithInstance("A"))
            val trust = FallbackTrust(probe::probe, { "A" })

            trust.allows("http://wan.example.com", null) shouldBe true
        }

        test("a candidate with no scheme is refused without probing") {
            val probe = FakeProbe(serverWithInstance("A"))
            val trust = FallbackTrust(probe::probe, { "A" })

            trust.allows("wan.example.com:8080", null) shouldBe false
            probe.calls shouldBe 0
        }

        test("a verdict is reused within the window and re-probed after it") {
            val probe = FakeProbe(serverWithInstance("A"))
            val time = TestTimeSource()
            val trust = FallbackTrust(probe::probe, { "A" }, time)

            trust.allows(wan, null) shouldBe true
            trust.allows(wan, null) shouldBe true
            probe.calls shouldBe 1

            time += FallbackTrust.VERDICT_TTL + 1.seconds
            trust.allows(wan, null) shouldBe true
            probe.calls shouldBe 2
        }

        test("a no-answer verdict is reused inside the short window") {
            val probe = FakeProbe(AppResult.Failure(TransportError.NetworkUnavailable()))
            val time = TestTimeSource()
            val trust = FallbackTrust(probe::probe, { "A" }, time)

            trust.allows(wan, null) shouldBe false
            time += 2.seconds
            trust.allows(wan, null) shouldBe false

            probe.calls shouldBe 1
        }

        test("a no-answer verdict is not reused after the short window") {
            val probe = FakeProbe(AppResult.Failure(TransportError.NetworkUnavailable()))
            val time = TestTimeSource()
            val trust = FallbackTrust(probe::probe, { "A" }, time)

            trust.allows(wan, null) shouldBe false
            time += 4.seconds
            trust.allows(wan, null) shouldBe false

            probe.calls shouldBe 2
        }

        test("a different-server refusal is still reused near the end of the long window") {
            val probe = FakeProbe(serverWithInstance("B"))
            val time = TestTimeSource()
            val trust = FallbackTrust(probe::probe, { "A" }, time)

            trust.allows(wan, null) shouldBe false
            time += 29.seconds
            trust.allows(wan, null) shouldBe false

            probe.calls shouldBe 1
        }

        test("a fresh ask re-probes immediately after a no-answer and allows the same server") {
            val probe = FakeProbe(AppResult.Failure(TransportError.NetworkUnavailable()))
            val trust = FallbackTrust(probe::probe, { "A" }, TestTimeSource())

            trust.allows(wan, null) shouldBe false
            probe.answer = serverWithInstance("A")
            trust.allows(wan, null, fresh = true) shouldBe true

            probe.calls shouldBe 2
        }

        test("a fresh ask does not re-probe after a different-server refusal inside the window") {
            val probe = FakeProbe(serverWithInstance("B"))
            val trust = FallbackTrust(probe::probe, { "A" }, TestTimeSource())

            trust.allows(wan, null) shouldBe false
            trust.allows(wan, null, fresh = true) shouldBe false

            probe.calls shouldBe 1
        }

        test("a refusal is reused within the window too") {
            val probe = FakeProbe(serverWithInstance("B"))
            val trust = FallbackTrust(probe::probe, { "A" })

            trust.allows(wan, null) shouldBe false
            trust.allows(wan, null) shouldBe false
            probe.calls shouldBe 1
        }

        test("a verdict for one candidate is not reused for another") {
            val probe = FakeProbe(serverWithInstance("A"))
            val trust = FallbackTrust(probe::probe, { "A" })

            trust.allows("https://x.example.com", null) shouldBe true
            probe.answer = serverWithInstance("B")
            trust.allows("https://y.example.com", null) shouldBe false
            probe.calls shouldBe 2
        }

        test("a change of connected server id invalidates the verdict") {
            val probe = FakeProbe(serverWithInstance("A"))
            var connected = "A"
            val trust = FallbackTrust(probe::probe, { connected })

            trust.allows(wan, null) shouldBe true
            connected = "C"
            trust.allows(wan, null) shouldBe false
            probe.calls shouldBe 2
        }

        test("cancellation of the probe propagates") {
            val trust =
                FallbackTrust(
                    probe = { throw CancellationException("x") },
                    connectedServerId = { "A" },
                )

            shouldThrow<CancellationException> { trust.allows(wan, null) }
        }
    })
