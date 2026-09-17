package com.calypsan.listenup.web.features.bookdetail

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.browser.window
import kotlin.js.Promise

/**
 * Handing a share to the browser.
 *
 * What these pin: a share sheet that took it says nothing more, a reader who CANCELS the sheet is
 * not treated as a failure and does not get their link copied behind their back, and a browser with
 * no sheet at all still gets the link onto the clipboard.
 */
class ShareOutcomeTest :
    FunSpec({
        val nav = window.navigator.asDynamic()
        val realShare = nav.share
        val realClipboard = nav.clipboard

        // ⛔ `navigator.clipboard` is an accessor with only a getter, so a plain assignment throws
        // "Cannot set property clipboard of #<Navigator>". Both stubs go through defineProperty for
        // the same reason, and `configurable` is what lets afterTest put the real one back.
        fun define(
            name: String,
            value: Any?,
        ) {
            val descriptor = js("({})")
            descriptor.value = value
            descriptor.configurable = true
            descriptor.writable = true
            js("Object").defineProperty(window.navigator, name, descriptor)
        }

        afterTest {
            define("share", realShare)
            define("clipboard", realClipboard)
        }

        fun stubShare(result: () -> Promise<dynamic>) {
            define("share", { _: Any? -> result() })
        }

        fun stubClipboard(onWrite: (String) -> Promise<dynamic>) {
            val c = js("({})")
            c.writeText = { text: String -> onWrite(text) }
            define("clipboard", c)
        }

        fun rejectWith(name: String): Promise<dynamic> {
            val err = js("({})")
            err.name = name
            err.message = "stubbed"
            return Promise.reject(Throwable(name).also { it.asDynamic().name = name })
        }

        test("a share sheet that accepts it reports SHARED") {
            stubShare { Promise.resolve<dynamic>(Unit) }
            var copied = false
            stubClipboard {
                copied = true
                Promise.resolve<dynamic>(Unit)
            }

            share() shouldBe ShareOutcome.SHARED
            copied shouldBe false
        }

        // ⛔ Cancelling the sheet is a completed interaction, not a failure. Falling through to the
        // clipboard here would copy a link the reader had just declined to send.
        test("a reader who cancels the sheet is not treated as a failure, and nothing is copied") {
            stubShare { rejectWith("AbortError") }
            var copied = false
            stubClipboard {
                copied = true
                Promise.resolve<dynamic>(Unit)
            }

            share() shouldBe ShareOutcome.SHARED
            copied shouldBe false
        }

        test("a sheet that genuinely fails falls back to the clipboard") {
            stubShare { rejectWith("NotAllowedError") }
            var written: String? = null
            stubClipboard { text ->
                written = text
                Promise.resolve<dynamic>(Unit)
            }

            share() shouldBe ShareOutcome.COPIED
            written shouldBe LINK
        }

        // The desktop case: most desktop browsers have no share sheet at all.
        test("no share sheet means the link goes to the clipboard") {
            define("share", null)
            var written: String? = null
            stubClipboard { text ->
                written = text
                Promise.resolve<dynamic>(Unit)
            }

            share() shouldBe ShareOutcome.COPIED
            written shouldBe LINK
        }

        test("neither route available reports FAILED rather than pretending") {
            define("share", null)
            define("clipboard", null)

            share() shouldBe ShareOutcome.FAILED
        }

        test("the link is what gets copied, not the sentence wrapped around it") {
            define("share", null)
            var written: String? = null
            stubClipboard { text ->
                written = text
                Promise.resolve<dynamic>(Unit)
            }

            share()

            written shouldBe LINK
        }
    })

private const val LINK = "https://listenup.example/share?book=b7"

private suspend fun share(): ShareOutcome = shareBookLink(title = "Elantris", text = "Check out Elantris on ListenUp!\n$LINK", url = LINK)
