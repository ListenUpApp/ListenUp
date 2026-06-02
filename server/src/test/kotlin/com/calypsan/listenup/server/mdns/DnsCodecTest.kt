package com.calypsan.listenup.server.mdns

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

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
    })
