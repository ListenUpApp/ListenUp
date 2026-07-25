package com.calypsan.listenup.client.data.sync

import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.sync.DomainDigest
import com.calypsan.listenup.client.data.remote.RpcChannel
import com.calypsan.listenup.client.data.remote.forTest
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

class DomainDigestClientTest :
    FunSpec({
        test("fetch passes the domain and cursor through to the service and returns its digest") {
            var requestedDomain: String? = null
            var requestedCursor: Long? = null
            val service =
                object : FakeSyncStreamService() {
                    override suspend fun digest(
                        domain: String,
                        cursor: Long,
                    ): AppResult<DomainDigest> {
                        requestedDomain = domain
                        requestedCursor = cursor
                        return AppResult.Success(DomainDigest(cursor = cursor, count = 3, hash = "sha256:abc"))
                    }
                }

            val client = DomainDigestClient(channel = RpcChannel.forTest(service))
            val result = client.fetch(domain = "series", cursor = 100L)

            result.shouldBeInstanceOf<AppResult.Success<DomainDigest>>()
            result.data.count shouldBe 3
            requestedDomain shouldBe "series"
            requestedCursor shouldBe 100L
        }
    })
