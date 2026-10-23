package com.calypsan.listenup.client.features.nowplaying.components

import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CastConnected
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.calypsan.listenup.client.design.MinTouchTarget
import com.calypsan.listenup.client.design.theme.Spacing
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.player_sheet_cast_playing_on
import org.jetbrains.compose.resources.stringResource

/**
 * The Cast / route-picker button for the Now Playing screen (spec §5.5.6, PO).
 *
 * At rest on Android it embeds the framework `MediaRouteButton`, which handles device discovery, the chooser and
 * controller dialogs, the connecting animation, and hides itself when no Cast devices are on the network. While
 * casting ([connectedDeviceName] non-null) it becomes [CastConnectedButton]: the device's name on `primaryContainer`,
 * which opens the same system dialog. On platforms without Cast (Desktop) it renders nothing.
 *
 * @param connectedDeviceName The device playing now, or null when playing on this device.
 */
@Composable
expect fun CastButton(
    connectedDeviceName: String?,
    modifier: Modifier = Modifier,
)

/**
 * Cast while casting (`States-Android`'s chip): the cast glyph and the device's name on `primaryContainer`, read as
 * "Cast. Playing on Living Room. Change output". The board's filled chip wins on looks (C13); the route chooser stays
 * the system's own dialog, which M3 does not restyle.
 *
 * @param deviceName The device playing now.
 * @param onOpenRoutes Open the system's Cast dialog for the connected route.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun CastConnectedButton(
    deviceName: String,
    onOpenRoutes: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val label = stringResource(Res.string.player_sheet_cast_playing_on, deviceName)
    FilledTonalButton(
        onClick = onOpenRoutes,
        shapes = ButtonDefaults.shapes(),
        colors =
            ButtonDefaults.filledTonalButtonColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            ),
        modifier =
            modifier.heightIn(min = MinTouchTarget).widthIn(max = MAX_WIDTH).clearAndSetSemantics {
                contentDescription = label
                role = Role.Button
                onClick {
                    onOpenRoutes()
                    true
                }
            },
    ) {
        Icon(Icons.Default.CastConnected, contentDescription = null, modifier = Modifier.size(18.dp))
        Text(
            text = deviceName,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(min = Spacing.xs),
        )
    }
}

/** The widest the chip grows, so a long device name ellipsises instead of crowding the row. */
private val MAX_WIDTH = 200.dp
