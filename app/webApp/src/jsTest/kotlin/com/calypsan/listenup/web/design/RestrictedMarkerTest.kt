package com.calypsan.listenup.web.design

import androidx.compose.runtime.CompositionLocalProvider
import com.calypsan.listenup.web.MountRegistry
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.browser.window
import org.w3c.dom.HTMLElement

/** The web lock: drawn for a restricted id, inside the cover's own box, named for a screen reader. */
class RestrictedMarkerTest :
    FunSpec({
        val mounts = MountRegistry()
        afterTest { mounts.disposeAll() }

        fun render(
            restricted: Set<String>,
            bookId: String = "b1",
        ): HTMLElement =
            mounts.mount {
                CompositionLocalProvider(LocalRestrictedBookIds provides restricted) {
                    Cover(title = "Dune", size = 140, overlay = { RestrictedMarker(bookId) })
                }
            }

        test("a restricted book's cover carries the lock, inside the cover box") {
            val host = render(setOf("b1"))
            val lock = host.querySelector(".cover > .lu-lock") as HTMLElement
            lock.getAttribute("role") shouldBe "img"
            lock.getAttribute("aria-label") shouldBe EXPECTED_LABEL
            lock.getAttribute("title") shouldBe EXPECTED_LABEL
        }

        test("any other book's cover carries none") {
            (render(setOf("b2")).querySelector(".lu-lock") == null) shouldBe true
        }

        test("outside a provider — a member — there is no lock") {
            val host = mounts.mount { Cover(title = "Dune", size = 140, overlay = { RestrictedMarker("b1") }) }
            (host.querySelector(".lu-lock") == null) shouldBe true
        }

        // Absolute is what keeps it from taking space: an in-flow lock would push the art down (and
        // a fluid card taller). Pinned by where it lands, not by comparing two equal sizes.
        test("the lock is absolute, inside the cover box at its top-left corner") {
            val host = render(setOf("b1"))
            val cover = host.querySelector(".cover") as HTMLElement
            val lock = host.querySelector(".lu-lock") as HTMLElement
            window.getComputedStyle(lock).position shouldBe "absolute"
            lock.shouldSitInsideTopLeftOf(cover)
        }
    })

private const val EXPECTED_LABEL = "In a collection, so only people it is shared with can see it."

/**
 * This lock's box lies wholly inside [cover]'s, in its top-left quarter — where an absolute lock
 * pinned to the corner lands, and where an in-flow one never does.
 */
internal fun HTMLElement.shouldSitInsideTopLeftOf(cover: HTMLElement) {
    val lock = getBoundingClientRect()
    val box = cover.getBoundingClientRect()
    (lock.width > 0.0 && lock.height > 0.0) shouldBe true
    (lock.left >= box.left && lock.top >= box.top) shouldBe true
    (lock.right <= box.right && lock.bottom <= box.bottom) shouldBe true
    (lock.left - box.left < box.width / 2 && lock.top - box.top < box.height / 2) shouldBe true
}
