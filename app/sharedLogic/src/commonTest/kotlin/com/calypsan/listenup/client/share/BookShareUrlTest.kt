package com.calypsan.listenup.client.share

import com.calypsan.listenup.api.dto.ServerInfo
import com.calypsan.listenup.client.domain.repository.InstanceRepository
import com.calypsan.listenup.core.BookId
import dev.mokkery.answering.returns
import dev.mokkery.everySuspend
import dev.mokkery.matcher.any
import dev.mokkery.mock
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe

class BookShareUrlTest :
    FunSpec({
        fun serverInfo(
            instanceId: String,
            remoteUrl: String?,
        ) = ServerInfo(
            name = "ListenUp",
            version = "1",
            apiVersion = "v1",
            setupRequired = false,
            remoteUrl = remoteUrl,
            instanceId = instanceId,
        )

        test("the link names the server, without a trailing slash, and decodes back to the book") {
            val repo = mock<InstanceRepository>()
            everySuspend { repo.getServerInfoOrNull(any()) } returns
                serverInfo(instanceId = "inst-1", remoteUrl = "https://books.example/")

            val url = bookShareUrl(repo, BookId("b1")).shouldNotBeNull()

            ShareLinkCodec.decode(url) shouldBe
                ShareTarget.Book(BookId("b1"), serverInstanceId = "inst-1", serverUrl = "https://books.example")
        }

        test("a server that can't say who it is gives no link rather than one that resolves nowhere") {
            val repo = mock<InstanceRepository>()
            everySuspend { repo.getServerInfoOrNull(any()) } returns null

            bookShareUrl(repo, BookId("b1")) shouldBe null
        }
    })
