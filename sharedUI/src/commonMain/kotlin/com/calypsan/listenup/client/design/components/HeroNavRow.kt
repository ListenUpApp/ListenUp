package com.calypsan.listenup.client.design.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.stringResource
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.common_back

private val HERO_NAV_BUTTON_SIZE = 48.dp

/**
 * The canonical floating navigation row for color-block detail heroes: a circular back
 * [IconButton] on the leading edge and an optional [actions] slot on the trailing edge,
 * with the system status-bar inset baked in so the controls always clear the system clock.
 *
 * This is the single inset-safe source of truth for the Profile / Series / Contributor
 * detail heroes — each floats edge-to-edge behind the status bar, so each must inset its
 * controls. Encapsulating the inset here makes the #595 / #563 class of bug (controls
 * trapped under the status bar) unrepresentable in those screens.
 *
 * @param onBack Invoked when the back button is tapped.
 * @param modifier Modifier for the row.
 * @param buttonBackground Frosted circular background behind the back control (typically the
 *   screen surface at ~50% alpha so the glyph reads on the color-block).
 * @param actions Trailing controls (an overflow menu, an edit button, …). Empty by default.
 */
@Composable
fun HeroNavRow(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    buttonBackground: Color = MaterialTheme.colorScheme.surface.copy(alpha = 0.5f),
    actions: @Composable RowScope.() -> Unit = {},
) {
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                // The hero Surface bleeds edge-to-edge behind the status bar; inset the
                // controls so the back/overflow buttons clear the system clock and stay tappable.
                .windowInsetsPadding(WindowInsets.statusBars)
                .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        IconButton(
            onClick = onBack,
            modifier = Modifier.size(HERO_NAV_BUTTON_SIZE).background(buttonBackground, CircleShape),
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = stringResource(Res.string.common_back),
                tint = MaterialTheme.colorScheme.onSurface,
            )
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            content = actions,
        )
    }
}
