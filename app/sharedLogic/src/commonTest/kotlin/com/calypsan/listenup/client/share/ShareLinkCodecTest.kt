package com.calypsan.listenup.client.share

import com.calypsan.listenup.core.BookId
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
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

            url shouldStartWith "https://link.listenup.audio/o?t=book"
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

            url shouldBe "https://link.listenup.audio/o?t=book&b=book-abc"
        }

        test("encode produces the https /o form for an invite") {
            val url = ShareLinkCodec.encode(ShareTarget.Invite(serverUrl = "https://lib.example.com", code = "JOIN9"))

            url shouldStartWith "https://link.listenup.audio/o?t=invite"
            url shouldContain "server=https%3A%2F%2Flib.example.com"
            url shouldContain "code=JOIN9"
        }

        test("encode puts the invite payload in the query, not the fragment, so it survives iOS Universal Link delivery") {
            val invite = ShareTarget.Invite(serverUrl = "http://192.168.86.24:8080", code = "JOIN9")
            val url = ShareLinkCodec.encode(invite)

            // iOS Universal Links (NSUserActivity.webpageURL) drop the #fragment; the payload must ride in ?query.
            url shouldContain "?t=invite"
            url shouldNotContain "#"
            // Simulate a delivery that strips everything from '#' onward: the invite still decodes.
            ShareLinkCodec.decode(url.substringBefore("#")) shouldBe invite
        }

        test("decode parses the https /o form from the fragment") {
            val target =
                ShareLinkCodec.decode(
                    "https://link.listenup.audio/o#t=book&b=book-abc&i=inst-123&u=https%3A%2F%2Flib.example.com",
                )

            target shouldBe
                ShareTarget.Book(
                    bookId = BookId("book-abc"),
                    serverInstanceId = "inst-123",
                    serverUrl = "https://lib.example.com",
                )
        }

        test("decode falls back to the query when the fragment is absent") {
            val target = ShareLinkCodec.decode("https://link.listenup.audio/o?t=book&b=book-xyz&i=inst-9")

            target shouldBe
                ShareTarget.Book(bookId = BookId("book-xyz"), serverInstanceId = "inst-9", serverUrl = null)
        }

        test("encode then decode round-trips a fully-populated book") {
            val original =
                ShareTarget.Book(
                    bookId = BookId("book-abc"),
                    serverInstanceId = "inst-123",
                    serverUrl = "https://lib.example.com:8443/listenup",
                )

            ShareLinkCodec.decode(ShareLinkCodec.encode(original)) shouldBe original
        }

        test("encode then decode round-trips an invite") {
            val original = ShareTarget.Invite(serverUrl = "https://lib.example.com", code = "JOIN9")

            ShareLinkCodec.decode(ShareLinkCodec.encode(original)) shouldBe original
        }

        test("encode carries the optional remote URL so an off-LAN invitee can connect") {
            val url =
                ShareLinkCodec.encode(
                    ShareTarget.Invite(
                        serverUrl = "http://192.168.1.5:8080",
                        code = "JOIN9",
                        remoteUrl = "https://lib.example.com",
                    ),
                )

            url shouldContain "server=http%3A%2F%2F192.168.1.5%3A8080"
            url shouldContain "remote=https%3A%2F%2Flib.example.com"
        }

        test("encode then decode round-trips an invite that carries a remote URL") {
            val original =
                ShareTarget.Invite(
                    serverUrl = "http://192.168.1.5:8080",
                    code = "JOIN9",
                    remoteUrl = "https://lib.example.com",
                )

            ShareLinkCodec.decode(ShareLinkCodec.encode(original)) shouldBe original
        }

        test("decode of a link with no remote param yields a null remoteUrl (backward compatible)") {
            val target =
                ShareLinkCodec.decode(
                    "https://link.listenup.audio/o?t=invite&server=http%3A%2F%2F192.168.1.5%3A8080&code=JOIN9",
                )

            target shouldBe ShareTarget.Invite(serverUrl = "http://192.168.1.5:8080", code = "JOIN9", remoteUrl = null)
        }

        test("decode parses the https /o form for an invite") {
            val target =
                ShareLinkCodec.decode(
                    "https://link.listenup.audio/o#t=invite&server=http%3A%2F%2F192.168.86.24%3A8080&code=ejOvmuYJBGyMDb2DoLgtsg",
                )

            target shouldBe
                ShareTarget.Invite(serverUrl = "http://192.168.86.24:8080", code = "ejOvmuYJBGyMDb2DoLgtsg")
        }

        test("decode parses the https /o/ form (trailing slash) for an invite") {
            val target =
                ShareLinkCodec.decode(
                    "https://link.listenup.audio/o/#t=invite&server=http%3A%2F%2F192.168.86.24%3A8080&code=ejOvmuYJBGyMDb2DoLgtsg",
                )

            target shouldBe
                ShareTarget.Invite(serverUrl = "http://192.168.86.24:8080", code = "ejOvmuYJBGyMDb2DoLgtsg")
        }

        test("decode returns null for an https invite missing the code") {
            ShareLinkCodec.decode("https://link.listenup.audio/o#t=invite&server=https%3A%2F%2Flib.example.com") shouldBe null
        }

        test("decode returns null for the removed listenup:// custom scheme") {
            ShareLinkCodec.decode("listenup://join?server=https%3A%2F%2Flib.example.com&code=ABC123") shouldBe null
            ShareLinkCodec.decode("listenup://book/book-legacy") shouldBe null
        }

        test("decode returns null for an https link with an unknown type") {
            ShareLinkCodec.decode("https://link.listenup.audio/o#t=podcast&b=x") shouldBe null
        }

        test("decode returns null for an https link missing the book id") {
            ShareLinkCodec.decode("https://link.listenup.audio/o#t=book") shouldBe null
        }

        test("decode returns null for a foreign https host") {
            ShareLinkCodec.decode("https://evil.example.com/o#t=book&b=x") shouldBe null
        }

        test("decode returns null for blank or unrelated input") {
            ShareLinkCodec.decode("") shouldBe null
            ShareLinkCodec.decode("not a url") shouldBe null
            ShareLinkCodec.decode("https://link.listenup.audio/other") shouldBe null
        }

        test("encode then decode round-trips a book with a non-ASCII server URL") {
            val original =
                ShareTarget.Book(
                    bookId = BookId("book-abc"),
                    serverInstanceId = "inst-1",
                    serverUrl = "https://bücher.example.com",
                )
            ShareLinkCodec.decode(ShareLinkCodec.encode(original)) shouldBe original
        }

        test("encode percent-encodes control characters with two-digit hex") {
            // Tab (0x09) must encode to %09, not the malformed %9.
            val url =
                ShareLinkCodec.encode(
                    ShareTarget.Book(bookId = BookId("book\tabc"), serverInstanceId = null, serverUrl = null),
                )
            url shouldContain "b=book%09abc"
        }

        test("decode returns null for a path extension on the /o route") {
            ShareLinkCodec.decode("https://link.listenup.audio/o/extra#t=book&b=x") shouldBe null
        }

        test("decode trims surrounding whitespace before parsing") {
            ShareLinkCodec.decode("  https://link.listenup.audio/o#t=book&b=book-trim\n") shouldBe
                ShareTarget.Book(bookId = BookId("book-trim"), serverInstanceId = null, serverUrl = null)
        }

        test("decode prefers the fragment over the query when both are present") {
            ShareLinkCodec.decode("https://link.listenup.audio/o?t=book&b=from-query#t=book&b=from-fragment") shouldBe
                ShareTarget.Book(bookId = BookId("from-fragment"), serverInstanceId = null, serverUrl = null)
        }
    })
