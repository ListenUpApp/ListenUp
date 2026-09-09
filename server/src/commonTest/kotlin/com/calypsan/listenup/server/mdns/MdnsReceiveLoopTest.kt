package com.calypsan.listenup.server.mdns

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/**
 * The receive loop treats `null` from [MdnsSocket.receive] as "socket closed, stop for good". The
 * native actual used to return `null` for a zero-length datagram as well, so one empty packet on
 * the wire silently ended discovery responses for the life of the process — on the binary that
 * ships — while the JVM actual (which returns an empty array) carried on. This pins the loop's side
 * of the contract with a scripted socket: an empty datagram is skipped, the next real browse is
 * still answered, and only `null` ends the loop. `commonTest`, so both lanes hold it.
 */
class MdnsReceiveLoopTest :
    FunSpec({

        test("an empty datagram does not end the loop — the next real browse is still answered") {
            val socket = ScriptedMdnsSocket(listOf(ByteArray(0), listenupBrowseQuery(), null))
            val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
            val responder = responder(scope)

            try {
                responder.receiveLoop(socket)
            } finally {
                scope.cancel()
            }

            // Read through the empty datagram and the browse, and stopped only at the close signal.
            socket.received shouldBe 3
            socket.sent shouldHaveSize 1
        }

        test("a closed socket ends the loop without answering anything") {
            val socket = ScriptedMdnsSocket(listOf(null))
            val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
            val responder = responder(scope)

            try {
                responder.receiveLoop(socket)
            } finally {
                scope.cancel()
            }

            socket.received shouldBe 1
            socket.sent.shouldBeEmpty()
        }
    })

private fun responder(scope: CoroutineScope): MulticastMdnsResponder =
    MulticastMdnsResponder(
        instanceName = "kotest-loop",
        port = 8080,
        txtProvider = { emptyMap() },
        scope = scope,
    )

/** Replays [datagrams] from [receive] in order — a `null` entry is the close signal — and records every [send]. */
private class ScriptedMdnsSocket(
    private val datagrams: List<ByteArray?>,
) : MdnsSocket {
    override val interfaceName: String = "fake0"
    override val ipv4: ByteArray = byteArrayOf(192.toByte(), 168.toByte(), 1, 2)
    val sent = mutableListOf<ByteArray>()
    var received = 0
        private set

    override fun send(payload: ByteArray) {
        sent += payload
    }

    override fun receive(): ByteArray? = datagrams.getOrNull(received++)

    override fun leaveAndClose() = Unit
}

/** A DNS query carrying one PTR question for our service type — what a browsing client sends. */
private fun listenupBrowseQuery(): ByteArray =
    byteArrayOf(0, 0, 0, 0, 0, 1, 0, 0, 0, 0, 0, 0) + // header, QDCOUNT = 1
        DnsCodec.encodeName(MdnsServiceInfo.SERVICE_TYPE) +
        byteArrayOf(0, 12, 0, 1) // QTYPE = PTR, QCLASS = IN
