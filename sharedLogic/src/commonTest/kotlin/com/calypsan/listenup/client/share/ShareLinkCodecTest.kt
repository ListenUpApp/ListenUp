package com.calypsan.listenup.client.share

import com.calypsan.listenup.core.BookId
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldStartWith

class ShareLinkCodecTest :
    FunSpec({

        test("encode produces the https /o form with the book id, instance id, and url") {
            val url =
                ShareLinkCodec.encode(
                    ShareTarget.Book(
                        bookId = BookId("book-abc"),
                        serverInstanceId = "inst-123",
                        serverUrl = "https://lib.example.com",
                    ),
                )

            url shouldStartWith "https://link.listenup.audio/o#t=book"
            url shouldContain "b=book-abc"
            url shouldContain "i=inst-123"
            // serverUrl is percent-encoded as a query component.
            url shouldContain "u=https%3A%2F%2Flib.example.com"
        }

        test("encode omits i and u when the book carries no server context") {
            val url =
                ShareLinkCodec.encode(
                    ShareTarget.Book(bookId = BookId("book-abc"), serverInstanceId = null, serverUrl = null),
                )

            url shouldBe "https://link.listenup.audio/o#t=book&b=book-abc"
        }

        test("encode produces the legacy listenup join form for an invite") {
            val url = ShareLinkCodec.encode(ShareTarget.Invite(serverUrl = "https://lib.example.com", code = "JOIN9"))

            url shouldStartWith "listenup://join?"
            url shouldContain "server=https%3A%2F%2Flib.example.com"
            url shouldContain "code=JOIN9"
        }
    })
