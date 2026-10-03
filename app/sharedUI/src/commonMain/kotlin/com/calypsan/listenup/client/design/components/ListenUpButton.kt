package com.calypsan.listenup.client.design.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.calypsan.listenup.client.design.haptics.LocalHaptics

/** The button's height at the default font: a 56dp pill. A label that wraps at a large font grows it. */
private val ButtonHeight = 56.dp

/** Material's disabled-outline opacity. */
private const val DISABLED_OUTLINE_ALPHA = 0.12f

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
 * @param danger `true` for a destructive action (Disconnect, Delete): the error colour takes the
 *   place of primary — an error fill when [filled], error text inside the outline otherwise
 *
 * The pill is 56dp tall at least, never exactly: at a large font a label wraps and the button grows
 * around it rather than clipping its second line. The outlined variant draws its edge in
 * [listenUpOutlinedBorder], so its tappable bounds stay visible.
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
    danger: Boolean = false,
) {
    val haptics = LocalHaptics.current
    val widthModifier = if (fillMaxWidth) Modifier.fillMaxWidth() else Modifier
    val spinnerColor = spinnerColor(filled = filled, danger = danger)
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
    // Compose won't invoke onClick on a disabled Button, so the haptic can't fire on one either.
    val clickWithHaptic: () -> Unit = {
        haptics.press()
        onClick()
    }
    if (filled) {
        Button(
            onClick = clickWithHaptic,
            enabled = enabled && !isLoading,
            shape = CircleShape,
            colors = filledColors(danger),
            modifier = modifier.then(widthModifier).heightIn(min = ButtonHeight),
        ) { label() }
    } else {
        OutlinedButton(
            onClick = clickWithHaptic,
            enabled = enabled && !isLoading,
            shape = CircleShape,
            colors = outlinedColors(danger),
            border = listenUpOutlinedBorder(enabled = enabled && !isLoading),
            modifier = modifier.then(widthModifier).heightIn(min = ButtonHeight),
        ) { label() }
    }
}

/** The loading spinner's colour: the ink the button's label would have. */
@Composable
private fun spinnerColor(
    filled: Boolean,
    danger: Boolean,
): Color {
    val colorScheme = MaterialTheme.colorScheme
    return when {
        filled && danger -> colorScheme.onError
        filled -> colorScheme.onPrimary
        danger -> colorScheme.error
        else -> colorScheme.primary
    }
}

@Composable
private fun filledColors(danger: Boolean): ButtonColors =
    if (danger) {
        ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.error,
            contentColor = MaterialTheme.colorScheme.onError,
        )
    } else {
        ButtonDefaults.buttonColors()
    }

@Composable
private fun outlinedColors(danger: Boolean): ButtonColors =
    if (danger) {
        ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
    } else {
        ButtonDefaults.outlinedButtonColors()
    }

/**
 * The edge of an outlined button: Material's `outline` role, not the faint `outlineVariant` Material 3
 * Expressive defaults to, so a low-vision reader can see where the button is (at least 3:1 against the
 * surfaces it sits on). Raw [OutlinedButton]s pass it as their `border`, so every outlined button in the app
 * has the same visible edge.
 *
 * @param enabled When false, the edge fades as Material's disabled outline does.
 */
@Composable
fun listenUpOutlinedBorder(enabled: Boolean = true): BorderStroke =
    BorderStroke(
        width = 1.dp,
        color =
            if (enabled) {
                MaterialTheme.colorScheme.outline
            } else {
                MaterialTheme.colorScheme.onSurface.copy(alpha = DISABLED_OUTLINE_ALPHA)
            },
    )
