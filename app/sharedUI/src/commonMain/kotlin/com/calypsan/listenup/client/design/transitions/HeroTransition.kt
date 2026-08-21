package com.calypsan.listenup.client.design.transitions

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.navigation3.ui.LocalNavAnimatedContentScope

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
fun Modifier.heroElement(key: Any?): Modifier {
    if (key == null) return this
    val transitionScope = LocalHeroTransitionScope.current ?: return this
    val contentScope = LocalHeroContentScope.current ?: return this
    return with(transitionScope) {
        this@heroElement.sharedElement(
            sharedContentState = rememberSharedContentState(key),
            animatedVisibilityScope = contentScope,
        )
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

/**
 * The hero key for a series' fanned cover deck, pairing a Series-list card with the detail hero.
 */
fun seriesHeroKey(seriesId: String): String = "hero:series:$seriesId"
