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
import androidx.compose.material3.TextFieldColors
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
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
 * Owns its caret locally (see [rememberOwnedTextFieldState]): [value] round-trips asynchronously
 * through the caller, so a stale echo of the user's own keystroke must never clamp the selection.
 *
 * @param value Current text value
 * @param onValueChange Callback when text changes. Callers must echo the propagated string back
 *   through [value] verbatim or not at all — never transformed (see [OwnedTextFieldState])
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
 * @param textStyle Input text style override. Null uses the [variant]'s default style
 * @param placeholderStyle Placeholder text style (including color) override. Null uses the
 *   [variant]'s default placeholder rendering
 * @param colors Field colors override. Null uses the [variant]'s default colors
 * @param shape Container shape override. Null uses [MaterialTheme.shapes.medium]
 * @param singleLine Whether the field is restricted to a single line of text
 * @param trailingContent Free-form trailing slot (e.g. a stateful action button). When non-null it
 *   wins over [trailingIcon] and [onTrailingClick]
 * @param transform THE sanctioned way to restrict or normalise input (digit-only fields, code
 *   alphabets, length caps). It runs INSIDE the component, before the echo ledger records
 *   anything, so the string propagated through [onValueChange] is already transformed and the
 *   caller's echo stays verbatim — the [OwnedTextFieldState] contract. Callers must NEVER
 *   transform in their own `onValueChange` lambda or ViewModel handler instead: a transforming
 *   caller makes every echo read as an external replacement and silently drops in-flight
 *   keystrokes under latency (see `CodeBoxes`, the precedent this parameter generalises). Must be
 *   idempotent (`transform(transform(s)) == transform(s)`) so an already-clean value passes
 *   through unchanged
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
    textStyle: TextStyle? = null,
    placeholderStyle: TextStyle? = null,
    colors: TextFieldColors? = null,
    shape: Shape? = null,
    singleLine: Boolean = true,
    trailingContent: (@Composable () -> Unit)? = null,
    transform: ((String) -> String)? = null,
) {
    val isHero = variant == ListenUpTextFieldVariant.Hero
    val heroTextStyle =
        MaterialTheme.typography.headlineSmall.copy(
            fontFamily = DisplayFontFamily,
            fontWeight = FontWeight.Bold,
        )
    val ownedText = rememberOwnedTextFieldState(value)
    OutlinedTextField(
        value = ownedText.fieldValue,
        onValueChange = { newValue ->
            // Transform before the ledger sees anything: local truth, the ledger entry, and the
            // propagated string are all the transformed text, so the caller's echo is verbatim.
            // When the transform rejects the whole edit (text unchanged after cleaning), edit()
            // reports no text change and the caller never hears about it — same net effect as the
            // old caller-side filter, without the divergent echo.
            val accepted = transform?.let { newValue.transformedBy(it) } ?: newValue
            if (ownedText.edit(accepted)) onValueChange(accepted.text)
        },
        label = label?.let { { Text(it) } },
        placeholder =
            placeholder?.let {
                {
                    when {
                        placeholderStyle != null -> {
                            Text(text = it, style = placeholderStyle)
                        }

                        isHero -> {
                            Text(
                                text = it,
                                style = heroTextStyle,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                            )
                        }

                        else -> {
                            Text(it)
                        }
                    }
                }
            },
        textStyle = textStyle ?: if (isHero) heroTextStyle else LocalTextStyle.current,
        enabled = enabled,
        isError = isError,
        supportingText = supportingText?.let { { Text(it) } },
        visualTransformation = visualTransformation,
        keyboardOptions = keyboardOptions,
        keyboardActions = keyboardActions,
        leadingIcon = leadingIcon?.let { icon -> { Icon(icon, contentDescription = null) } },
        trailingIcon =
            trailingContent
                ?: trailingIcon?.let { icon ->
                    {
                        if (onTrailingClick != null) {
                            IconButton(onClick = onTrailingClick) { Icon(icon, contentDescription = null) }
                        } else {
                            Icon(icon, contentDescription = null)
                        }
                    }
                },
        colors =
            colors
                ?: if (isHero) {
                    OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f),
                        unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
                        focusedContainerColor = heroContainerColor.copy(alpha = 0.4f),
                        unfocusedContainerColor = heroContainerColor.copy(alpha = 0.2f),
                    )
                } else {
                    OutlinedTextFieldDefaults.colors()
                },
        singleLine = singleLine,
        shape = shape ?: MaterialTheme.shapes.medium,
        modifier = modifier.fillMaxWidth(),
    )
}

/**
 * Applies [transform] to this value's text. An identity result returns the value untouched —
 * clean input must keep the user's caret exactly where the edit put it. A changed result rebuilds
 * the value with the caret clamped to `min(selection.end, newLength)`: for the common cases (a
 * rejected character mid-text, a length cap trimming the tail) that lands the caret where the
 * surviving text ends relative to the edit. Rebuilding discards any IME composition span, which
 * is acceptable for the restricted alphabets [transform] exists to enforce (see `CodeBoxes`).
 */
private fun TextFieldValue.transformedBy(transform: (String) -> String): TextFieldValue {
    val transformed = transform(text)
    if (transformed == text) return this
    return TextFieldValue(transformed, TextRange(minOf(selection.end, transformed.length)))
}
