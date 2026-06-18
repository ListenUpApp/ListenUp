package com.calypsan.listenup.server.mdns

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

class DnsCodecTest :
    FunSpec({
        test("encodeName writes length-prefixed labels terminated by a zero byte") {
            DnsCodec.encodeName("local").toList() shouldBe
                byteArrayOf(
                    5,
                    'l'.code.toByte(),
                    'o'.code.toByte(),
                    'c'.code.toByte(),
                    'a'.code.toByte(),
                    'l'.code.toByte(),
                    0,
                ).toList()
        }

        test("encodeName handles a multi-label service type") {
            DnsCodec.encodeName("_tcp.local").toList() shouldBe
                byteArrayOf(
                    4,
                    '_'.code.toByte(),
                    't'.code.toByte(),
                    'c'.code.toByte(),
                    'p'.code.toByte(),
                    5,
                    'l'.code.toByte(),
                    'o'.code.toByte(),
                    'c'.code.toByte(),
                    'a'.code.toByte(),
                    'l'.code.toByte(),
                    0,
                ).toList()
        }

        test("encodeResponse produces a response packet whose answer count covers PTR, meta-PTR, SRV, TXT, A") {
            val service =
                MdnsServiceInfo(
                    instanceName = "test-host",
                    port = 8080,
                    txt = linkedMapOf("id" to "abc", "name" to "ListenUp", "version" to "0.0.1", "api" to "v1"),
                )
            val packet = DnsCodec.encodeResponse(service, ipv4 = byteArrayOf(192.toByte(), 168.toByte(), 1, 50), ttlSeconds = 120)

            // Header: bytes 2-3 = 0x8400 flags, bytes 6-7 = ANCOUNT = 5.
            packet[2] shouldBe 0x84.toByte()
            packet[3] shouldBe 0x00.toByte()
            ((packet[6].toInt() and 0xFF) shl 8 or (packet[7].toInt() and 0xFF)) shouldBe 5

            // TXT payload appears verbatim.
            String(packet, Charsets.US_ASCII) shouldContain "id=abc"
            String(packet, Charsets.US_ASCII) shouldContain "name=ListenUp"
        }

        test("encodeResponse owns the host (SRV target + A) under hostLabel, not the instance name") {
            // The host record must resolve to OUR unique label so it can't be answered by the host's
            // avahi for the OS hostname (which would inject every interface address, docker/VPN incl.).
            val service =
                MdnsServiceInfo(
                    instanceName = "omarchy",
                    port = 8080,
                    txt = linkedMapOf("id" to "abc"),
                    hostLabel = "listenup-abc",
                )
            val packet = DnsCodec.encodeResponse(service, ipv4 = byteArrayOf(192.toByte(), 168.toByte(), 86, 39), ttlSeconds = 120)
            val wire = String(packet, Charsets.US_ASCII)

            // The service instance still browses under the instance label…
            wire shouldContain "omarchy"
            // …but the SRV target / A owner is the unique host label.
            wire shouldContain "listenup-abc"
        }

        test("encodeResponse emits a remote= TXT pair when present") {
            val service =
                MdnsServiceInfo(
                    instanceName = "host",
                    port = 8080,
                    txt = buildMdnsTxt("abc", "Simon's Library", "https://listen.example.com"),
                )
            val packet = DnsCodec.encodeResponse(service, ipv4 = byteArrayOf(10, 0, 0, 1), ttlSeconds = 120)
            String(packet, Charsets.US_ASCII) shouldContain "name=Simon's Library"
            String(packet, Charsets.US_ASCII) shouldContain "remote=https://listen.example.com"
        }

        test("encodeResponse with ttl 0 is a goodbye packet and is non-empty") {
            val service = MdnsServiceInfo("h", 8080, mapOf("id" to "x"))
            val packet = DnsCodec.encodeResponse(service, byteArrayOf(10, 0, 0, 1), ttlSeconds = 0)
            (packet.size > 12) shouldBe true
        }

        test("questionNames extracts the queried name from a PTR query packet") {
            val q = java.io.ByteArrayOutputStream()
            q.write(byteArrayOf(0, 0, 0, 0, 0, 1, 0, 0, 0, 0, 0, 0)) // header, QDCOUNT=1
            q.write(DnsCodec.encodeName("_listenup._tcp.local"))
            q.write(byteArrayOf(0, 12)) // QTYPE = PTR
            q.write(byteArrayOf(0, 1)) // QCLASS = IN
            DnsCodec.questionNames(q.toByteArray()) shouldBe listOf("_listenup._tcp.local")
        }

        test("questionNames returns empty for a malformed or questionless packet") {
            DnsCodec.questionNames(byteArrayOf(0, 0)) shouldBe emptyList()
        }

        test("isQueryForUs is true for our service type and false for an unrelated query") {
            val ours = java.io.ByteArrayOutputStream()
            ours.write(byteArrayOf(0, 0, 0, 0, 0, 1, 0, 0, 0, 0, 0, 0))
            ours.write(DnsCodec.encodeName("_listenup._tcp.local"))
            ours.write(byteArrayOf(0, 12, 0, 1))
            DnsCodec.isQueryForUs(ours.toByteArray()) shouldBe true

            val other = java.io.ByteArrayOutputStream()
            other.write(byteArrayOf(0, 0, 0, 0, 0, 1, 0, 0, 0, 0, 0, 0))
            other.write(DnsCodec.encodeName("_http._tcp.local"))
            other.write(byteArrayOf(0, 12, 0, 1))
            DnsCodec.isQueryForUs(other.toByteArray()) shouldBe false
        }
    })
