package com.calypsan.listenup.client.design.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

/**
 * Material 3 primary button with expressive pill shape and animated states.
 *
 * Uses [CircleShape] for the fully-rounded M3 Expressive look.
 * Loading state transitions smoothly with crossfade animation.
 *
 * @param text Button text
 * @param onClick Callback when button is clicked
 * @param modifier Optional modifier
 * @param enabled Whether button is interactive
 * @param isLoading Whether to show loading spinner (animates transition)
 * @param leadingIcon Optional icon shown before the text (hidden while loading)
 */
@Composable
fun ListenUpButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    isLoading: Boolean = false,
    leadingIcon: ImageVector? = null,
) {
    Button(
        onClick = onClick,
        enabled = enabled && !isLoading,
        shape = CircleShape,
        modifier =
            modifier
                .fillMaxWidth()
                .height(56.dp),
    ) {
        AnimatedContent(
            targetState = isLoading,
            transitionSpec = {
                fadeIn() togetherWith fadeOut() using SizeTransform(clip = false)
            },
            label = "ButtonContent",
        ) { loading ->
            if (loading) {
                ListenUpLoadingIndicatorSmall(
                    color = MaterialTheme.colorScheme.onPrimary,
                )
            } else if (leadingIcon != null) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(ButtonDefaults.IconSpacing),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = leadingIcon,
                        contentDescription = null,
                        modifier = Modifier.size(ButtonDefaults.IconSize),
                    )
                    Text(text = text, style = MaterialTheme.typography.titleMedium)
                }
            } else {
                Text(
                    text = text,
                    style = MaterialTheme.typography.titleMedium,
                )
            }
        }
    }
}
