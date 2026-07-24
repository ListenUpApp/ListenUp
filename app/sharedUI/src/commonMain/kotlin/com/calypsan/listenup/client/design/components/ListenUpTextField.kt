package com.calypsan.listenup.client.design.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.VisualTransformation
import com.calypsan.listenup.client.design.theme.DisplayFontFamily

/**
 * Visual variant of [ListenUpTextField].
 */
enum class ListenUpTextFieldVariant {
    /** Standard labeled form field — the default for every form row. */
    Standard,

    /**
     * Large editorial inline editor: display type, bold, translucent tinted container, no floating
     * label. For a hero/title field (e.g. the contributor name) that should still be *the* canonical
     * text field, not a bespoke `OutlinedTextField`.
     */
    Hero,
}

/**
 * Material 3 text field using the theme's expressive shape system.
 *
 * Uses [MaterialTheme.shapes.medium] for consistent corner radius across the app.
 * Inherits dynamic color support from the theme.
 *
 * @param value Current text value
 * @param onValueChange Callback when text changes
 * @param label Floating label text. Null renders no label (the default for [ListenUpTextFieldVariant.Hero]).
 * @param modifier Optional modifier
 * @param placeholder Hint text shown when empty
 * @param enabled Whether the field is enabled for input
 * @param isError Whether to show error styling
 * @param supportingText Helper or error text below field
 * @param visualTransformation Visual transformation applied to text (e.g., password masking)
 * @param keyboardOptions Keyboard type and IME action configuration
 * @param keyboardActions Keyboard action handlers
 * @param leadingIcon Optional icon shown at the start of the field
 * @param trailingIcon Optional icon shown at the end of the field
 * @param onTrailingClick When non-null, the trailing icon becomes a clickable button
 *   (e.g. a password visibility toggle)
 * @param variant [ListenUpTextFieldVariant.Standard] (default) or [ListenUpTextFieldVariant.Hero]
 * @param heroContainerColor Tint for the [ListenUpTextFieldVariant.Hero] translucent container.
 *   Ignored by [ListenUpTextFieldVariant.Standard]. Defaults to the theme surface.
 */
@Composable
fun ListenUpTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String? = null,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    enabled: Boolean = true,
    isError: Boolean = false,
    supportingText: String? = null,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    leadingIcon: ImageVector? = null,
    trailingIcon: ImageVector? = null,
    onTrailingClick: (() -> Unit)? = null,
    variant: ListenUpTextFieldVariant = ListenUpTextFieldVariant.Standard,
    heroContainerColor: Color = MaterialTheme.colorScheme.surface,
) {
    val isHero = variant == ListenUpTextFieldVariant.Hero
    val heroTextStyle =
        MaterialTheme.typography.headlineSmall.copy(
            fontFamily = DisplayFontFamily,
            fontWeight = FontWeight.Bold,
        )
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = label?.let { { Text(it) } },
        placeholder =
            placeholder?.let {
                {
                    if (isHero) {
                        Text(
                            text = it,
                            style = heroTextStyle,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                        )
                    } else {
                        Text(it)
                    }
                }
            },
        textStyle = if (isHero) heroTextStyle else LocalTextStyle.current,
        enabled = enabled,
        isError = isError,
        supportingText = supportingText?.let { { Text(it) } },
        visualTransformation = visualTransformation,
        keyboardOptions = keyboardOptions,
        keyboardActions = keyboardActions,
        leadingIcon = leadingIcon?.let { icon -> { Icon(icon, contentDescription = null) } },
        trailingIcon =
            trailingIcon?.let { icon ->
                {
                    if (onTrailingClick != null) {
                        IconButton(onClick = onTrailingClick) { Icon(icon, contentDescription = null) }
                    } else {
                        Icon(icon, contentDescription = null)
                    }
                }
            },
        colors =
            if (isHero) {
                OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f),
                    unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
                    focusedContainerColor = heroContainerColor.copy(alpha = 0.4f),
                    unfocusedContainerColor = heroContainerColor.copy(alpha = 0.2f),
                )
            } else {
                OutlinedTextFieldDefaults.colors()
            },
        singleLine = true,
        shape = MaterialTheme.shapes.medium,
        modifier = modifier.fillMaxWidth(),
    )
}
