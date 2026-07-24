package com.calypsan.listenup.client.share

import com.calypsan.listenup.core.BookId
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class ShareTargetResolverTest :
    FunSpec({

        fun book(
            instanceId: String?,
            url: String? = "https://lib.example.com",
        ) = ShareTarget.Book(bookId = BookId("book-1"), serverInstanceId = instanceId, serverUrl = url)

        test("an invite always resolves to OpenInviteClaim regardless of connected server") {
            val resolution =
                ShareTargetResolver.resolve(
                    ShareTarget.Invite(serverUrl = "https://lib.example.com", code = "JOIN9"),
                    connectedInstanceId = "inst-other",
                )

            resolution shouldBe ShareResolution.OpenInviteClaim("https://lib.example.com", "JOIN9")
        }

        test("a book on the connected server resolves to OpenBook") {
            ShareTargetResolver.resolve(book(instanceId = "inst-1"), connectedInstanceId = "inst-1") shouldBe
                ShareResolution.OpenBook(BookId("book-1"))
        }

        test("a book on a different server resolves to WrongServer with the source url") {
            ShareTargetResolver.resolve(book(instanceId = "inst-1"), connectedInstanceId = "inst-2") shouldBe
                ShareResolution.WrongServer("https://lib.example.com")
        }

        test("any book resolves to NotConnected when no server is connected") {
            ShareTargetResolver.resolve(book(instanceId = "inst-1"), connectedInstanceId = null) shouldBe
                ShareResolution.NotConnected("https://lib.example.com")
        }

        test("a legacy book (no server context) opens against the connected server") {
            ShareTargetResolver.resolve(book(instanceId = null, url = null), connectedInstanceId = "inst-1") shouldBe
                ShareResolution.OpenBook(BookId("book-1"))
        }

        test("a legacy book resolves to NotConnected when no server is connected") {
            ShareTargetResolver.resolve(book(instanceId = null, url = null), connectedInstanceId = null) shouldBe
                ShareResolution.NotConnected(null)
        }
    })
