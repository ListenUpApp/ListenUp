package com.calypsan.listenup.client.design.util

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * Counts the screens that currently hold work a navigation from outside the app must not discard —
 * an upload in flight, an edit not yet saved.
 *
 * Screens stay stateless about navigation: they raise a hold with [HoldUnsavedWork], and the
 * navigation host reads [isHeld] before it would clear the back stack for a push tap, a shared link
 * or a launcher shortcut.
 */
@Stable
class UnsavedWorkHolds {
    private var count by mutableIntStateOf(0)

    /** Whether any composed screen is holding unsaved work right now. */
    val isHeld: Boolean get() = count > 0

    internal fun raise() {
        count++
    }

    internal fun lower() {
        count--
    }
}

/**
 * The holds for the current navigation host. A host that never resets its stack from outside (the
 * desktop app) can leave the default, which nothing reads.
 */
val LocalUnsavedWorkHolds = staticCompositionLocalOf { UnsavedWorkHolds() }

/**
 * Holds the current screen's work for as long as [active] is true and the screen is composed.
 *
 * Pair it with the screen's `PlatformBackHandler` and give it the same condition: Back asks before
 * discarding, and this makes an outside navigation respect the same question.
 */
@Composable
fun HoldUnsavedWork(active: Boolean) {
    val holds = LocalUnsavedWorkHolds.current
    DisposableEffect(holds, active) {
        if (active) holds.raise()
        onDispose { if (active) holds.lower() }
    }
}
