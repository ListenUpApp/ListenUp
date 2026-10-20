package com.calypsan.listenup.web.design

import com.calypsan.listenup.client.data.covertheme.browserCoverThemeRepository
import com.calypsan.listenup.core.BookId
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.first

/** The browser half of spec §4.4, against real `fetch`, `createImageBitmap` and canvas. */
class BrowserCoverThemeRepositoryTest :
    FunSpec({
        fun dataUrl(base64: String) = "data:image/png;base64,$base64"

        test("a green cover gives a green-leaning wash in both appearances") {
            val repository = browserCoverThemeRepository { _, _ -> dataUrl(GREEN_8X8) }
            val themes = repository.observe(BookId("b1"), "h1", WebCoverThemeStyle).first().shouldNotBeNull()
            // Light is mixed into the warm paper, so green ties red there (#E6E6DE); dark leads with green (#2E312D).
            val (lightRed, lightGreen, lightBlue) = listOf(16, 8, 0).map { (themes.light.washArgb shr it) and 0xFF }
            (lightGreen >= lightRed && lightGreen > lightBlue) shouldBe true
            val (darkRed, darkGreen, darkBlue) = listOf(16, 8, 0).map { (themes.dark.washArgb shr it) and 0xFF }
            (darkGreen > darkRed && darkGreen > darkBlue) shouldBe true
        }

        test("a transparent cover is neutral") {
            browserCoverThemeRepository { _, _ -> dataUrl(CLEAR_8X8) }
                .observe(BookId("b1"), "h1", WebCoverThemeStyle)
                .first() shouldBe null
        }

        test("a URL that is not an image is neutral, not an error, and is fetched again next time") {
            var fetches = 0
            val repository =
                browserCoverThemeRepository { _, _ ->
                    fetches++
                    "data:text/plain,not-an-image"
                }
            repository.observe(BookId("b1"), "h1", WebCoverThemeStyle).first() shouldBe null
            repository.observe(BookId("b1"), "h1", WebCoverThemeStyle).first() shouldBe null
            fetches shouldBe 2
        }

        test("a revisit is answered from memory with no second fetch") {
            var fetches = 0
            val repository =
                browserCoverThemeRepository { _, _ ->
                    fetches++
                    dataUrl(GREEN_8X8)
                }
            val first = repository.observe(BookId("b1"), "h1", WebCoverThemeStyle).first()
            repository.cached(BookId("b1"), "h1", WebCoverThemeStyle) shouldBe first
            fetches shouldBe 1
        }
    })

private const val GREEN_8X8 =
    "iVBORw0KGgoAAAANSUhEUgAAAAgAAAAICAYAAADED76LAAAAEklEQVR42mOwrwr9jw8zjAwFAG6Vg0Ebc4V6AAAAAElFTkSuQmCC"
private const val CLEAR_8X8 =
    "iVBORw0KGgoAAAANSUhEUgAAAAgAAAAICAYAAADED76LAAAADUlEQVR42mNgGAUgAAABCAABXbcZDQAAAABJRU5ErkJggg=="
