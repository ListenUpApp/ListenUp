package com.calypsan.listenup.client

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain

/**
 * Tests for [redactLinkForLog] — how a deep-link URL is rendered into a log line.
 *
 * The bug that motivates this: `MainActivity.handleIntent` logged the whole raw URL when a VIEW
 * intent failed to decode. An invite link carries `code=` — a bearer secret that admits its holder
 * to the server — and every Android log call is teed to a file that the Settings "Share logs"
 * action hands to an arbitrary app of the user's choosing. A raw URL in a log line is therefore a
 * credential in a shared file.
 *
 * Parameter NAMES must survive: they are what makes the line diagnosable ("the link had no `t`").
 */
class ShareLinkLoggingTest :
    FunSpec({

        test("an invite link keeps every parameter name and no parameter value") {
            val raw = "https://link.listenup.audio/o?t=invite&server=https%3A%2F%2Fhome.example&code=SECRET123"

            val redacted = redactLinkForLog(raw)

            redacted shouldBe "https://link.listenup.audio/o?t&server&code"
            redacted shouldNotContain "SECRET123"
            redacted shouldNotContain "home.example"
        }

        test("a book link redacts the same way") {
            val raw = "https://link.listenup.audio/o?t=book&server=https%3A%2F%2Fhome.example&id=book-42"

            val redacted = redactLinkForLog(raw)

            redacted shouldBe "https://link.listenup.audio/o?t&server&id"
            redacted shouldNotContain "book-42"
        }

        test("a URL with no query is returned unchanged") {
            redactLinkForLog("https://link.listenup.audio/o") shouldBe "https://link.listenup.audio/o"
        }

        test("a legacy fragment payload never appears") {
            // ShareLinkCodec also accepts the payload in a #fragment, so a code can arrive there.
            val redacted = redactLinkForLog("https://link.listenup.audio/o#t=invite&code=SECRET")

            redacted shouldBe "https://link.listenup.audio/o#…"
            redacted shouldNotContain "SECRET"
        }

        test("an empty string returns an empty string") {
            redactLinkForLog("") shouldBe ""
        }

        test("a bare question mark does not crash") {
            redactLinkForLog("https://x/o?") shouldBe "https://x/o"
        }
    })
