package com.calypsan.listenup.web.design

import androidx.compose.runtime.CompositionLocalProvider
import com.calypsan.listenup.web.MountRegistry
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
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

        test("the lock takes no space: the cover box is the same size with or without it") {
            val with = render(setOf("b1")).querySelector(".cover") as HTMLElement
            val without = render(emptySet()).querySelector(".cover") as HTMLElement
            with.offsetHeight shouldBe without.offsetHeight
            with.offsetWidth shouldBe without.offsetWidth
        }
    })

private const val EXPECTED_LABEL = "In a collection, so only people it is shared with can see it."
