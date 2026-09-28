package com.calypsan.listenup.client.navigation

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerKeyboardModifiers
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.WindowInfo
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntSize
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.NavMetadataKey
import androidx.navigation3.runtime.contains
import androidx.navigation3.runtime.metadata
import androidx.navigation3.scene.Scene
import androidx.navigation3.scene.SceneStrategy
import androidx.navigation3.scene.SceneStrategyScope
import com.calypsan.listenup.client.design.LocalInDetailPane
import com.calypsan.listenup.client.design.TwoPaneMinWidth
import com.calypsan.listenup.client.design.transitions.LocalHeroTransitionScope

/** The list pane's share of the window; the detail takes the rest, since it is the richer screen. */
private const val LIST_PANE_FRACTION = 0.4f

/**
 * A list and the detail opened from it, side by side: Series or Contributor on the left, the book
 * on the right.
 *
 * Each pane is told its own size (see [PaneSized]), so a screen that picks its layout from the
 * window width picks it from the pane instead. The detail pane is marked with [LocalInDetailPane]
 * so its back arrow becomes a Close.
 *
 * Heroes are switched off inside the scene. A container transform grows a cover out of the card that
 * was tapped and replaces it; here the card stays on screen in the list, so the same cover would be
 * visible twice under one hero key. The panes still move: NavDisplay carries the list entry from its
 * full-width scene into this one as a shared element, so the list narrows into its pane while the
 * detail fades in beside it, and widens back as it closes.
 */
internal data class ListDetailScene<T : Any>(
    override val key: Any,
    override val previousEntries: List<NavEntry<T>>,
    val listEntry: NavEntry<T>,
    val detailEntry: NavEntry<T>,
) : Scene<T> {
    override val entries: List<NavEntry<T>> = listOf(listEntry, detailEntry)

    override val content: @Composable () -> Unit = {
        CompositionLocalProvider(LocalHeroTransitionScope provides null) {
            Row(modifier = Modifier.fillMaxSize()) {
                PaneSized(modifier = Modifier.weight(LIST_PANE_FRACTION).fillMaxHeight()) {
                    listEntry.Content()
                }
                VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                PaneSized(modifier = Modifier.weight(1f - LIST_PANE_FRACTION).fillMaxHeight()) {
                    CompositionLocalProvider(LocalInDetailPane provides true) {
                        // The scene is keyed by its list, so opening another book from the same list
                        // keeps this scene and swaps only the detail — the list never re-enters.
                        val motion = MaterialTheme.motionScheme
                        AnimatedContent(
                            targetState = detailEntry,
                            contentKey = { entry -> entry.contentKey },
                            transitionSpec = {
                                fadeIn(motion.defaultEffectsSpec()) togetherWith fadeOut(motion.fastEffectsSpec())
                            },
                            label = "detail pane",
                        ) { entry -> entry.Content() }
                    }
                }
            }
        }
    }

    companion object {
        /** Metadata for an entry that can be the list pane: a screen whose rows open a detail. */
        fun listPane(): Map<String, Any> = metadata { put(ListPaneKey, true) }

        /** Metadata for an entry that opens beside the list it came from. */
        fun detailPane(): Map<String, Any> = metadata { put(DetailPaneKey, true) }
    }

    /** Marks an entry that can be shown as the list pane. */
    object ListPaneKey : NavMetadataKey<Boolean>

    /** Marks an entry that can be shown as the detail pane. */
    object DetailPaneKey : NavMetadataKey<Boolean>
}

/**
 * Pairs a detail on top of the back stack with the list directly beneath it, from [TwoPaneMinWidth].
 *
 * Below that width, or when the stack is shaped any other way, it declines and NavDisplay falls back
 * to its single-pane scene — the compact path is the untouched default, not a branch of this one.
 *
 * The list must sit *directly* beneath the detail. A list further down is a screen the user has
 * since left: pairing it with a later detail would hide whatever they opened in between.
 */
internal class ListDetailSceneStrategy<T : Any>(
    private val windowWidth: Dp,
) : SceneStrategy<T> {
    override fun SceneStrategyScope<T>.calculateScene(entries: List<NavEntry<T>>): Scene<T>? {
        if (windowWidth < TwoPaneMinWidth || entries.size < 2) return null
        val detailEntry = entries.last()
        val listEntry = entries[entries.lastIndex - 1]
        if (ListDetailScene.DetailPaneKey !in detailEntry.metadata) return null
        if (ListDetailScene.ListPaneKey !in listEntry.metadata) return null
        return ListDetailScene(
            key = listEntry.contentKey,
            previousEntries = entries.dropLast(1),
            listEntry = listEntry,
            detailEntry = detailEntry,
        )
    }
}

/** A [ListDetailSceneStrategy] for the current window width, rebuilt only when the width changes. */
@Composable
internal fun <T : Any> rememberListDetailSceneStrategy(): ListDetailSceneStrategy<T> {
    val windowWidth =
        with(LocalDensity.current) {
            LocalWindowInfo.current.containerSize.width
                .toDp()
        }
    return remember(windowWidth) { ListDetailSceneStrategy(windowWidth) }
}

/**
 * Lays [content] out in a pane and tells it the pane is its window.
 *
 * Screens choose between their compact and wide layouts from `currentWindowAdaptiveInfo()`, which
 * reads [LocalWindowInfo]'s container size. Inside a pane that is the wrong question: a book detail
 * in the right 60% of a 1280dp window has 768dp, not 1280dp, and its wide layout needs 840dp.
 * Overriding the container size here answers every such screen with its pane's size at once, instead
 * of teaching each of them about panes. Focus and keyboard state still come from the real window.
 */
@Composable
internal fun PaneSized(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    BoxWithConstraints(modifier = modifier) {
        val window = LocalWindowInfo.current
        val paneSize = IntSize(constraints.maxWidth, constraints.maxHeight)
        val paneDpSize = DpSize(maxWidth, maxHeight)
        val paneWindow = remember(window, paneSize, paneDpSize) { PaneWindowInfo(window, paneSize, paneDpSize) }
        CompositionLocalProvider(LocalWindowInfo provides paneWindow) {
            Box(modifier = Modifier.fillMaxSize()) { content() }
        }
    }
}

/** The real window's [WindowInfo], reporting a pane's size as the container size. */
private class PaneWindowInfo(
    private val window: WindowInfo,
    override val containerSize: IntSize,
    override val containerDpSize: DpSize,
) : WindowInfo {
    override val isWindowFocused: Boolean get() = window.isWindowFocused
    override val keyboardModifiers: PointerKeyboardModifiers get() = window.keyboardModifiers
}
