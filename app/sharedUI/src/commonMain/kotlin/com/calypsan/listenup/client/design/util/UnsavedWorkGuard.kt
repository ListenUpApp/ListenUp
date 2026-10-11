package com.calypsan.listenup.client.design.util

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.calypsan.listenup.client.design.components.UnsavedChangesDialog

/**
 * Guards a screen's unsaved edit, the way every edit screen does it.
 *
 * While [dirty] is true the system Back asks before leaving, an outside navigation (a push tap, a
 * shared link, a launcher shortcut) holds off via [HoldUnsavedWork], and the returned function —
 * which the screen's own back or Cancel affordance calls — asks the same question. Clean, it leaves at
 * once. The question is the shared [UnsavedChangesDialog]; there is no second copy of it.
 *
 * @param dirty whether the screen holds a draft that differs from what it opened on.
 * @param onLeave leaves the screen; called straight away when clean, or after Discard.
 * @return the function a back or Cancel affordance calls instead of [onLeave].
 */
@Composable
fun rememberUnsavedWorkGuard(
    dirty: Boolean,
    onLeave: () -> Unit,
): () -> Unit {
    var asking by remember { mutableStateOf(false) }

    PlatformBackHandler(enabled = dirty) { asking = true }
    HoldUnsavedWork(active = dirty)

    if (asking && dirty) {
        UnsavedChangesDialog(
            onDiscard = {
                asking = false
                onLeave()
            },
            onKeepEditing = { asking = false },
        )
    }

    return { if (dirty) asking = true else onLeave() }
}
