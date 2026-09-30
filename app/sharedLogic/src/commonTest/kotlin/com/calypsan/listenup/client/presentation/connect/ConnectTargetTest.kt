package com.calypsan.listenup.client.presentation.connect

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * [connectTarget] turns a typed URL into the host and port the local-network gate is asked about.
 * The host must be the bare address: iOS's `nw_endpoint_create_host` does not take URL syntax, so
 * a bracketed or percent-encoded host would make the probe fail and the denial go unreported.
 */
class ConnectTargetTest :
    FunSpec({
        test("an IPv4 address without a scheme keeps its port") {
            connectTarget("192.168.1.5:8080") shouldBe ConnectTarget("192.168.1.5", 8080)
        }

        test("a name without a port gets the https default") {
            connectTarget("nas.local") shouldBe ConnectTarget("nas.local", 443)
        }

        test("an IPv6 host loses its URL brackets") {
            connectTarget("[fd00::5]:8080") shouldBe ConnectTarget("fd00::5", 8080)
        }

        test("an IPv6 zone is percent-decoded") {
            connectTarget("http://[fe80::1%25en0]:8080") shouldBe ConnectTarget("fe80::1%en0", 8080)
        }
    })
