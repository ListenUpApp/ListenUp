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

        test("a Chromium attachment is driven by hls.js, not by the browser") {
            // The one that matters. attachHls once branched on canPlayType, which answers "maybe"
            // in Chromium for a format Chromium cannot decode at all — so every Chrome and Firefox
            // user took the native branch and hls.js, the entire point of the transcode path, was
            // never involved. It failed as an opaque media error far downstream of the cause, and
            // no test noticed.
            //
            // Asserted through the same seam a browser would take: any browser reporting MSE must
            // come back hls.js-backed.
            Hls.isSupported() shouldBe true

            val element = document.createElement("audio") as HTMLAudioElement
            val handle = attachHls(element, "/kotest-absent.m3u8", onFatalError = {})

            handle.usesHlsJs shouldBe true
            handle.destroy()
        }

        test("Chromium claims HLS support it does not have, which is why canPlayType is not the test") {
            // Pins the browser behaviour the design rests on. If a future Chromium answered "" the
            // old code would have been accidentally right; if it answered "probably" a
            // probably-only probe would be wrong too. Either way the ordering above must be
            // revisited, so the surprising answer is recorded here rather than in a comment alone.
            val element = document.createElement("audio") as HTMLAudioElement

            element.canPlayType("application/vnd.apple.mpegurl").toString() shouldBe "maybe"
        }
    })
