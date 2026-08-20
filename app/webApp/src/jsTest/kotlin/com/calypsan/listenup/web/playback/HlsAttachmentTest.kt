package com.calypsan.listenup.web.playback

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.browser.document
import org.w3c.dom.HTMLAudioElement

/**
 * Proves the hls.js binding at runtime, which is the only place it can be proved: an
 * `external class` compiles against nothing, so a wrong module specifier or an import that
 * lands on the module namespace instead of the class is invisible until a browser runs it.
 *
 * Chromium — what this lane runs — has no native HLS but does have MSE, so it takes the hls.js
 * branch, which is the branch worth checking.
 */
class HlsAttachmentTest :
    FunSpec({

        test("the hls.js import resolves to a constructible class, not a module namespace") {
            // `Hls()` on a namespace object throws "is not a constructor" — the exact failure a
            // compile-only check cannot see.
            Hls().destroy()
        }

        test("this browser reports MSE support, so the transcode path has a decoder") {
            Hls.isSupported() shouldBe true
        }

        test("attaching a playlist yields a handle that can be destroyed") {
            val element = document.createElement("audio") as HTMLAudioElement

            val handle = attachHls(element, "/kotest-absent.m3u8", onFatalError = {})

            handle shouldNotBe null
            // Destroying immediately is also the assertion: it aborts the in-flight manifest
            // load, which is exactly what a segment change has to do.
            handle.destroy()
        }
    })
