package com.calypsan.listenup.client.design.motion

import android.content.ContentResolver
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

/**
 * Provides [LocalReduceMotion] and [LocalTouchExplorationActive] from the system, observed live: a
 * change in Settings or TalkBack switching on takes effect without restarting the activity.
 */
@Composable
fun ProvideMotionPreferences(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val animationsRemoved = rememberAnimationsRemoved(context.contentResolver)
    val touchExploration =
        context.getSystemService(AccessibilityManager::class.java)?.let { rememberTouchExploration(it) }
    CompositionLocalProvider(
        LocalReduceMotion provides animationsRemoved.value,
        LocalTouchExplorationActive provides (touchExploration?.value == true),
        content = content,
    )
}

/** "Remove animations" sets the animator duration scale to zero; that is the whole signal. */
private fun animationsRemoved(resolver: ContentResolver): Boolean =
    Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f

@Composable
private fun rememberAnimationsRemoved(resolver: ContentResolver): State<Boolean> {
    val removed = remember(resolver) { mutableStateOf(animationsRemoved(resolver)) }
    DisposableEffect(resolver) {
        val observer =
            object : ContentObserver(Handler(Looper.getMainLooper())) {
                override fun onChange(selfChange: Boolean) {
                    removed.value = animationsRemoved(resolver)
                }
            }
        resolver.registerContentObserver(
            Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE),
            false,
            observer,
        )
        onDispose { resolver.unregisterContentObserver(observer) }
    }
    return removed
}

@Composable
private fun rememberTouchExploration(manager: AccessibilityManager): State<Boolean> {
    val enabled = remember(manager) { mutableStateOf(manager.isTouchExplorationEnabled) }
    DisposableEffect(manager) {
        val listener = AccessibilityManager.TouchExplorationStateChangeListener { enabled.value = it }
        manager.addTouchExplorationStateChangeListener(listener)
        onDispose { manager.removeTouchExplorationStateChangeListener(listener) }
    }
    return enabled
}
