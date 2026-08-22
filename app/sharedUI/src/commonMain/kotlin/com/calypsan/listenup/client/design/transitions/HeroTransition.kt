package com.calypsan.listenup.client.design.transitions

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.animation.BoundsTransform
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.navigation3.ui.LocalNavAnimatedContentScope
import androidx.navigation3.ui.NavDisplay

/**
 * The [SharedTransitionScope] published by the `SharedTransitionLayout` wrapping the authenticated
 * `NavDisplay`, or null when there isn't one.
 *
 * Null is the honest default, not a defect: desktop does not use Navigation 3 at all, and the
 * pre-auth graphs have no hero pairs. Every consumer degrades to a plain, un-animated render.
 */
val LocalHeroTransitionScope: ProvidableCompositionLocal<SharedTransitionScope?> =
    compositionLocalOf { null }

/**
 * How long a hero takes, and therefore how long the screens under it take to cross-fade.
 *
 * The two are one animation and have to be spent together. Left to their own defaults they are not:
 * the fade is a tween and the element's bounds a spring, so the destination finishes assembling
 * while its hero is still in the air — the page arrives, then the thing it is about turns up
 * afterwards, which reads as a stutter rather than a movement.
 */
private const val HERO_DURATION_MS = 350

/** Bounds spec for [heroElement], spending exactly [HERO_DURATION_MS] so it lands with the fade. */
@OptIn(ExperimentalSharedTransitionApi::class)
private val heroBounds = BoundsTransform { _, _ -> tween(HERO_DURATION_MS) }

/**
 * The per-destination [AnimatedVisibilityScope] that drives a hero's progress, or null outside a
 * `NavEntry`.
 *
 * This exists because Navigation 3's own `LocalNavAnimatedContentScope` **throws** when read outside
 * an entry, and our cover components are also rendered by the now-playing bar and the search overlay,
 * which are not entries. [HeroEntry] narrows that hard failure to a null this local can carry safely.
 */
val LocalHeroContentScope: ProvidableCompositionLocal<AnimatedVisibilityScope?> =
    compositionLocalOf { null }

/**
 * Publishes the enclosing `NavEntry`'s animated-content scope so hero elements inside [content] can
 * pair with their counterpart on the destination screen.
 *
 * Wrap the content of any entry that holds one half of a hero pair. Calling this anywhere other than
 * directly inside an `entry<T> { }` block passed to `NavDisplay` will throw — that is Navigation 3's
 * contract, and the reason this wrapper is explicit rather than a decorator.
 */
@Composable
fun HeroEntry(content: @Composable () -> Unit) {
    CompositionLocalProvider(
        LocalHeroContentScope provides LocalNavAnimatedContentScope.current,
        content = content,
    )
}

/**
 * Marks this element as one half of a hero pair identified by [key], animating its bounds into the
 * matching element on the destination screen.
 *
 * A null [key] opts out, and so does the absence of either scope, so this is safe to leave wired up
 * on a component that is also rendered outside the navigation graph. [key] must be unique among the
 * elements composed at any one moment — see [bookCoverHeroKey] for the naming scheme.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun Modifier.heroElement(
    key: Any?,
    clipShape: Shape? = null,
): Modifier {
    if (key == null) return this
    val transitionScope = LocalHeroTransitionScope.current ?: return this
    val contentScope = LocalHeroContentScope.current ?: return this
    return with(transitionScope) {
        val state = rememberSharedContentState(key)
        if (clipShape == null) {
            this@heroElement.sharedElement(
                sharedContentState = state,
                animatedVisibilityScope = contentScope,
                boundsTransform = heroBounds,
            )
        } else {
            // A shared element is drawn in an overlay ABOVE the normal hierarchy, so it loses the
            // ancestor that was rounding it — the grid clips on the cell's own Box, the detail side
            // on ElevatedCard's shape, and neither travels with the element. Without a clip of its
            // own the cover flies square-cornered and snaps to shape only on arrival.
            this@heroElement
                .clip(clipShape)
                .sharedElement(
                    sharedContentState = state,
                    animatedVisibilityScope = contentScope,
                    boundsTransform = heroBounds,
                    clipInOverlayDuringTransition = OverlayClip(clipShape),
                )
        }
    }
}

/**
 * The hero key for a book's cover art, pairing a library/browse card with the book detail hero.
 *
 * Namespaced by entity so a book and a contributor that happen to share an id can never collide.
 */
fun bookCoverHeroKey(bookId: String): String = "hero:book-cover:$bookId"

/**
 * The hero key for a contributor's portrait, pairing a Contributors-list avatar with the detail header.
 */
fun contributorHeroKey(contributorId: String): String = "hero:contributor:$contributorId"

// Series deliberately has no hero key. Its artwork on both sides is a FannedDeck built from the
// series' books, and unlike a book cover (resolvable from a bookId) or a contributor portrait
// (from a contributorId), that deck cannot be drawn before the detail screen's data loads — the
// destination would have nothing for the element to land on. Giving series a hero means carrying
// the deck's book ids on the navigation route, which every site that opens a series would have to
// supply. A fanned stack of five covers also lacks the single-object identity a container
// transform depends on, so it is a poor candidate even setting the plumbing aside. Series keeps
// the app's ordinary slide.

/**
 * Entry metadata for a destination that carries a hero: the screens cross-fade instead of sliding.
 *
 * The app-wide horizontal slide and a hero are two answers to the same question, and running both at
 * once produces neither. The slide translates whole screens while the shared element travels between
 * two points in absolute coordinates, so the cover comes unglued from the cell it is supposed to be
 * growing out of — it sails across the screen while the grid slides the other way beneath it. A
 * container transform is meant to *replace* the slide, not accompany it.
 *
 * Applied per entry rather than globally so every screen without a hero keeps the slide it had.
 */
val heroEntryTransitions: Map<String, Any>
    get() =
        NavDisplay.transitionSpec { heroFade() } +
            NavDisplay.popTransitionSpec { heroFade() } +
            NavDisplay.predictivePopTransitionSpec { _ -> heroFade() }

/** The screen cross-fade, spending the same [HERO_DURATION_MS] the element does. */
private fun heroFade() = fadeIn(tween(HERO_DURATION_MS)) togetherWith fadeOut(tween(HERO_DURATION_MS))
