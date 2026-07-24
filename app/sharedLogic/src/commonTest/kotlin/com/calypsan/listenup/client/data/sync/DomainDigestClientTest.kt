package com.calypsan.listenup.client.data.sync

import com.calypsan.listenup.api.contractJson
import com.calypsan.listenup.api.sync.DomainDigest
import com.calypsan.listenup.api.result.AppResult
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json

class DomainDigestClientTest :
    FunSpec({
        test("fetch GETs the domain digest endpoint at the given cursor") {
            var requestedUrl = ""
            val mockClient =
                HttpClient(
                    MockEngine { req ->
                        requestedUrl = req.url.toString()
                        respond(
                            content = """{"cursor":100,"count":3,"hash":"sha256:abc"}""",
                            headers = headersOf(HttpHeaders.ContentType, "application/json"),
                        )
                    },
                ) { install(ContentNegotiation) { json(contractJson) } }

            val client =
                DomainDigestClient(
                    httpClientProvider = { mockClient },
                    serverUrlProvider = { "http://test" },
                )
            val result = client.fetch(domain = "series", cursor = 100L)

            result.shouldBeInstanceOf<AppResult.Success<DomainDigest>>()
            result.data.count shouldBe 3
            requestedUrl shouldBe "http://test/api/v1/sync/series/digest?cursor=100"
        }
    })
