package com.calypsan.listenup.client.design.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.calypsan.listenup.client.design.haptics.LocalHaptics
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.common_save
import listenup.composeapp.generated.resources.common_saving
import org.jetbrains.compose.resources.stringResource

/**
 * An edit screen's Save, as a top-bar action: a filled pill that is **truly** disabled
 * (`enabled = false`) until there is something to save, and while the save is in flight.
 *
 * It replaces the Save FAB, which only recoloured when "disabled" and stayed clickable, so
 * TalkBack announced an action that did nothing. A FAB is for one primary creation action;
 * committing an edit belongs with the screen's title, where Material puts it.
 *
 * @param onClick Saves. Never invoked while disabled or busy.
 * @param enabled Whether there are changes to save.
 * @param modifier Modifier for the button.
 * @param isBusy Whether a save (or a save-class operation) is running; shows a spinner and
 *   disables the action.
 * @param busyLabel What the action reads while [isBusy]; defaults to "Saving…".
 */
@Composable
fun SaveAction(
    onClick: () -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    isBusy: Boolean = false,
    busyLabel: String? = null,
) {
    val haptics = LocalHaptics.current
    Button(
        // Compose never invokes onClick on a disabled Button, so the haptic can't fire on one.
        onClick = {
            haptics.press()
            onClick()
        },
        enabled = enabled && !isBusy,
        shape = CircleShape,
        modifier = modifier,
    ) {
        if (isBusy) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(ButtonDefaults.IconSpacing),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ListenUpLoadingIndicator(
                    size = SPINNER_SIZE,
                    color = LocalContentColor.current,
                )
                Text(busyLabel ?: stringResource(Res.string.common_saving))
            }
        } else {
            Text(stringResource(Res.string.common_save))
        }
    }
}

private val SPINNER_SIZE = 18.dp
