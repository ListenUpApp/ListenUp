package com.calypsan.listenup.server.mdns

import com.calypsan.listenup.server.api.ServerIdentity
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.maps.shouldContain
import io.kotest.matchers.maps.shouldNotContainKey
import io.kotest.matchers.shouldBe

class MdnsTxtTest :
    FunSpec({
        test("name= reflects the operator server name; id/version/api always present") {
            val txt = buildMdnsTxt(instanceId = "abc", serverName = "Simon's Library", remoteUrl = null)
            txt shouldContain ("id" to "abc")
            txt shouldContain ("name" to "Simon's Library")
            txt shouldContain ("version" to ServerIdentity.VERSION)
            txt shouldContain ("api" to ServerIdentity.API_VERSION)
        }
        test("remote= present when a remote URL is configured") {
            val txt = buildMdnsTxt("abc", "ListenUp", "https://listen.example.com")
            txt shouldContain ("remote" to "https://listen.example.com")
        }
        test("remote omitted when remoteUrl is null or blank") {
            buildMdnsTxt("abc", "ListenUp", null) shouldNotContainKey "remote"
            buildMdnsTxt("abc", "ListenUp", "   ") shouldNotContainKey "remote"
        }
        test("an over-255-octet remote value is omitted, not emitted (would crash the encoder)") {
            val longUrl = "https://" + "x".repeat(300) + ".example.com"
            buildMdnsTxt("abc", "ListenUp", longUrl) shouldNotContainKey "remote"
        }
        test("insertion order is id, name, version, api, then remote") {
            val txt = buildMdnsTxt("abc", "ListenUp", "https://r.example.com")
            txt.keys.toList() shouldBe listOf("id", "name", "version", "api", "remote")
        }
    })
