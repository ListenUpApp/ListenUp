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
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

/**
 * The canonical Material 3 Expressive button — a fully-rounded pill with an animated
 * loading state.
 *
 * Defaults to a full-width filled primary button. Opt into variants with the flags:
 * [filled] `= false` for the outlined (secondary) treatment, [fillMaxWidth] `= false`
 * to wrap the content width (e.g. inline in an action row). Supports an optional
 * [leadingIcon] and/or [trailingIcon]; both are hidden while [isLoading].
 *
 * @param text Button text
 * @param onClick Callback when the button is clicked
 * @param modifier Optional modifier
 * @param enabled Whether the button is interactive
 * @param isLoading Whether to show the loading spinner (animates the transition)
 * @param filled `true` for the filled primary look, `false` for the outlined look
 * @param fillMaxWidth `true` to span the available width, `false` to wrap content
 * @param leadingIcon Optional icon shown before the text (hidden while loading)
 * @param trailingIcon Optional icon shown after the text (hidden while loading)
 */
@Composable
fun ListenUpButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    isLoading: Boolean = false,
    filled: Boolean = true,
    fillMaxWidth: Boolean = true,
    leadingIcon: ImageVector? = null,
    trailingIcon: ImageVector? = null,
) {
    val widthModifier = if (fillMaxWidth) Modifier.fillMaxWidth() else Modifier
    val spinnerColor =
        if (filled) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.primary
    val label: @Composable () -> Unit = {
        AnimatedContent(
            targetState = isLoading,
            transitionSpec = {
                fadeIn() togetherWith fadeOut() using SizeTransform(clip = false)
            },
            label = "ButtonContent",
        ) { loading ->
            if (loading) {
                ListenUpLoadingIndicatorSmall(color = spinnerColor)
            } else {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(ButtonDefaults.IconSpacing),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (leadingIcon != null) {
                        Icon(
                            imageVector = leadingIcon,
                            contentDescription = null,
                            modifier = Modifier.size(ButtonDefaults.IconSize),
                        )
                    }
                    Text(text = text, style = MaterialTheme.typography.titleMedium)
                    if (trailingIcon != null) {
                        Icon(
                            imageVector = trailingIcon,
                            contentDescription = null,
                            modifier = Modifier.size(ButtonDefaults.IconSize),
                        )
                    }
                }
            }
        }
    }
    if (filled) {
        Button(
            onClick = onClick,
            enabled = enabled && !isLoading,
            shape = CircleShape,
            modifier = modifier.then(widthModifier).height(56.dp),
        ) { label() }
    } else {
        OutlinedButton(
            onClick = onClick,
            enabled = enabled && !isLoading,
            shape = CircleShape,
            modifier = modifier.then(widthModifier).height(56.dp),
        ) { label() }
    }
}
