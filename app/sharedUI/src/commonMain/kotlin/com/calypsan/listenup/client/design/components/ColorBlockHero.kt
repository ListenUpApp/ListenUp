package com.calypsan.listenup.client.design.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.stringResource
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.common_back

private val HERO_BADGE_SIZE = 48.dp

/**
 * The canonical color-blocked screen header: a [MaterialTheme.colorScheme.primaryContainer] [Surface]
 * with large rounded bottom corners holding a back [IconButton], an optional UPPERCASE [overline]
 * (e.g. the server name), a large emphasized [title] in `onPrimaryContainer`, and a trailing
 * [ScallopBadge] glyph. An optional [supportingText] paragraph renders below the title.
 *
 * Shared across the admin surfaces — the Admin landing screen and the Create-Invite screen both
 * compose this so the color-blocked hero stays a single source of truth.
 *
 * @param title Large emphasized heading rendered in `onPrimaryContainer`.
 * @param badgeIcon Glyph rendered inside the trailing scallop badge.
 * @param onBack Invoked when the back button is tapped.
 * @param modifier Modifier for the hero surface.
 * @param overline Optional UPPERCASE eyebrow above the title (e.g. server name); hidden when null
 *   or blank.
 * @param supportingText Optional paragraph rendered below the title.
 * @param content Optional trailing slot rendered full-width below the title/supporting text — used
 *   to host a [WizardStepTracker] inside the wizard chrome.
 */
@Composable
fun ColorBlockHero(
    title: String,
    badgeIcon: ImageVector,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    overline: String? = null,
    supportingText: String? = null,
    content: @Composable (ColumnScope.() -> Unit)? = null,
) {
    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        shape = RoundedCornerShape(bottomStart = 32.dp, bottomEnd = 32.dp),
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    // The primaryContainer Surface bleeds edge-to-edge behind the status bar; inset
                    // only the content so the back button clears the system clock and stays tappable.
                    .windowInsetsPadding(WindowInsets.statusBars)
                    .padding(start = 8.dp, end = 20.dp, top = 8.dp, bottom = 24.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                IconButton(onClick = onBack) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                        contentDescription = stringResource(Res.string.common_back),
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    if (!overline.isNullOrBlank()) {
                        Text(
                            text = overline.uppercase(),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Text(
                        text = title,
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
                ScallopBadge(size = HERO_BADGE_SIZE, containerColor = MaterialTheme.colorScheme.primary) {
                    Icon(
                        imageVector = badgeIcon,
                        contentDescription = null,
                        modifier = Modifier.size(26.dp),
                        tint = MaterialTheme.colorScheme.onPrimary,
                    )
                }
            }
            if (!supportingText.isNullOrBlank()) {
                Text(
                    text = supportingText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.86f),
                    modifier = Modifier.padding(start = 8.dp, top = 14.dp, end = 8.dp),
                )
            }
            if (content != null) {
                Column(modifier = Modifier.padding(start = 8.dp, top = 18.dp)) {
                    content()
                }
            }
        }
    }
}
