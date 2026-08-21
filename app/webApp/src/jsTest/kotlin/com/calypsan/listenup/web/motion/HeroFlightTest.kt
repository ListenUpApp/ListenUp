package com.calypsan.listenup.web.motion

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.browser.document
import org.w3c.dom.Element
import org.w3c.dom.HTMLElement
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/**
 * The cover's flight between the grid and the book, in both directions.
 *
 * These are geometry tests, not choreography ones: what they pin is that an origin gets recorded
 * at all and that a flight is actually scheduled. Every earlier failure of this feature was a
 * silent one — an origin that was never recorded, or a rect of zeros — and each looked identical
 * to "working" from anywhere except a count of `Element.animate` calls.
 */
class HeroFlightTest :
    FunSpec({

        val attached = mutableListOf<Element>()

        fun box(
            left: Int,
            top: Int,
            size: Int,
        ): Element {
            val element = document.createElement("div") as HTMLElement
            element.style.position = "fixed"
            element.style.left = "${left}px"
            element.style.top = "${top}px"
            element.style.width = "${size}px"
            element.style.height = "${size}px"
            document.body?.appendChild(element)
            attached += element
            return element
        }

        afterTest {
            // The origin is module state; a test that leaves one behind arms the next one. Leaving
            // a page with no mounted hero is exactly what clears it.
            attached.forEach { releaseHero(it) }
            captureHeroOriginBeforeRouteChange()
            attached.forEach { it.remove() }
            attached.clear()
        }

        test("a cover captured while it is still on screen flies the tile it returns to") {
            val hero = box(left = 500, top = 100, size = 180)
            trackHero("book-1", hero)

            captureHeroOriginBeforeRouteChange()
            releaseHero(hero)
            hero.remove()

            val tile = box(left = 280, top = 178, size = 208)
            flyHeroInto("book-1", CoverSurface.GRID, tile)
            nextFrame()

            tile.animationCount() shouldBe 1
        }

        // ⛔ This is the return-leg bug, kept as a test because it was invisible: the outbound
        // flight worked, so everything LOOKED wired, and only a count of `animate` calls showed
        // the return leg firing zero times. Compose detaches a node during `applyChanges` and
        // dispatches remember-observers afterwards, so an `onDispose` block reads a rect of zeros.
        test("a cover measured after it leaves the document records nothing to fly from") {
            val hero = box(left = 500, top = 100, size = 180)
            trackHero("book-2", hero)
            hero.remove()

            captureHeroOriginBeforeRouteChange()

            val tile = box(left = 280, top = 178, size = 208)
            flyHeroInto("book-2", CoverSurface.GRID, tile)
            nextFrame()

            tile.animationCount() shouldBe 0
        }

        test("a flight is consumed by its arrival, so a re-render does not replay it") {
            val hero = box(left = 500, top = 100, size = 180)
            trackHero("book-3", hero)
            captureHeroOriginBeforeRouteChange()

            val first = box(left = 280, top = 178, size = 208)
            flyHeroInto("book-3", CoverSurface.GRID, first)
            nextFrame()

            val second = box(left = 280, top = 178, size = 208)
            flyHeroInto("book-3", CoverSurface.GRID, second)
            nextFrame()

            first.animationCount() shouldBe 1
            second.animationCount() shouldBe 0
        }

        test("a cover arriving back on the surface it left does not consume the origin") {
            val hero = box(left = 500, top = 100, size = 180)
            trackHero("book-4", hero)
            captureHeroOriginBeforeRouteChange()

            val sameSurface = box(left = 500, top = 100, size = 180)
            flyHeroInto("book-4", CoverSurface.HERO, sameSurface)
            nextFrame()

            val tile = box(left = 280, top = 178, size = 208)
            flyHeroInto("book-4", CoverSurface.GRID, tile)
            nextFrame()

            sameSurface.animationCount() shouldBe 0
            tile.animationCount() shouldBe 1
        }

        test("a different book's cover does not fly") {
            val hero = box(left = 500, top = 100, size = 180)
            trackHero("book-5", hero)
            captureHeroOriginBeforeRouteChange()

            val tile = box(left = 280, top = 178, size = 208)
            flyHeroInto("another-book", CoverSurface.GRID, tile)
            nextFrame()

            tile.animationCount() shouldBe 0
        }

        test("a hero origin does not survive a navigation that passes through another page") {
            val hero = box(left = 500, top = 100, size = 180)
            trackHero("book-7", hero)
            captureHeroOriginBeforeRouteChange()

            // Book → Settings: the hero unmounts with the page, and Settings has none of its own.
            releaseHero(hero)
            hero.remove()
            // Settings → Library.
            captureHeroOriginBeforeRouteChange()

            val tile = box(left = 280, top = 178, size = 208)
            flyHeroInto("book-7", CoverSurface.GRID, tile)
            nextFrame()

            tile.animationCount() shouldBe 0
        }

        test("releasing the mounted hero leaves nothing to capture") {
            val hero = box(left = 500, top = 100, size = 180)
            trackHero("book-6", hero)
            releaseHero(hero)

            captureHeroOriginBeforeRouteChange()

            val tile = box(left = 280, top = 178, size = 208)
            flyHeroInto("book-6", CoverSurface.GRID, tile)
            nextFrame()

            tile.animationCount() shouldBe 0
        }
    })

/** How many animations this element is running — the only honest evidence a flight happened. */
private fun Element.animationCount(): Int = asDynamic().getAnimations().length as Int

/** Resolves after the next animation frame, which is when a flight is scheduled to start. */
private suspend fun nextFrame(): Unit =
    suspendCoroutine { continuation ->
        kotlinx.browser.window.requestAnimationFrame {
            kotlinx.browser.window.requestAnimationFrame { continuation.resume(Unit) }
        }
    }
