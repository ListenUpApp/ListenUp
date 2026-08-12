package com.calypsan.listenup.web

import com.calypsan.listenup.client.core.CookieMirroringSecureStorage
import com.calypsan.listenup.core.BrowserSecureStorage
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import kotlinx.browser.document
import kotlin.random.Random

/**
 * Pins the cookie the DOM uses to authenticate cover requests.
 *
 * Both failure directions are silent, which is why this exists. A cookie that is never written
 * produces broken images — and an `<img>` reports a 401 as nothing at all. A cookie that outlives
 * sign-out leaves a live credential in the browser after the user believes they are gone.
 *
 * The name must match the server's `ACCESS_TOKEN_COOKIE`; nothing enforces that across the module
 * boundary, so the literal is asserted here rather than read from a shared constant that does not
 * exist.
 */
class CookieMirrorTest :
    FunSpec({

        fun freshStorage(): CookieMirroringSecureStorage {
            val namespace = "test-${Random.nextInt(0, Int.MAX_VALUE)}"
            return CookieMirroringSecureStorage(BrowserSecureStorage(namespace))
        }

        test("saving the access token writes the cookie") {
            val storage = freshStorage()

            storage.save("access_token", "token-abc")

            document.cookie shouldContain "listenup_access=token-abc"
        }

        test("saving any other key leaves the cookie alone") {
            // Refresh tokens most of all: nothing in the DOM needs to present one, so nothing in
            // the DOM should be able to read one.
            val storage = freshStorage()
            storage.delete("access_token")

            storage.save("refresh_token", "must-not-appear")

            document.cookie shouldNotContain "must-not-appear"
        }

        test("a later save overwrites the cookie rather than leaving the old token") {
            // The token is ≤15m and the refresh path saves through this same seam. If a rewrite did
            // not land, covers would start 401-ing mid-session with nothing in the console.
            val storage = freshStorage()

            storage.save("access_token", "token-first")
            storage.save("access_token", "token-second")

            document.cookie shouldContain "listenup_access=token-second"
            document.cookie shouldNotContain "token-first"
        }

        test("deleting the access token clears the cookie") {
            val storage = freshStorage()
            storage.save("access_token", "token-abc")

            storage.delete("access_token")

            document.cookie shouldNotContain "token-abc"
        }

        test("clear() clears the cookie") {
            val storage = freshStorage()
            storage.save("access_token", "token-abc")

            storage.clear()

            document.cookie shouldNotContain "token-abc"
        }

        test("reads still reach the delegate") {
            val storage = freshStorage()
            storage.save("access_token", "token-abc")

            storage.read("access_token") shouldBe "token-abc"
        }
    })
