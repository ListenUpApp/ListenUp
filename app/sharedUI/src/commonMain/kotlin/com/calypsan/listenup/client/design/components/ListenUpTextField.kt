package com.calypsan.listenup.client.design.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.foundation.text.input.KeyboardActionHandler
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.TextObfuscationMode
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedSecureTextField
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldColors
import androidx.compose.material3.TextFieldLabelScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import com.calypsan.listenup.client.design.haptics.LocalHaptics
import com.calypsan.listenup.client.design.theme.DisplayFontFamily
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.common_hide_password
import listenup.composeapp.generated.resources.common_show_password
import org.jetbrains.compose.resources.stringResource

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
 * The caller owns the [state]; the field owns the text and the caret, so nothing round-trips
 * through a ViewModel while the person is typing. Seed it with `rememberTextFieldState(initial)`,
 * read edits with [ForwardEdits], and replace its text with `setTextAndPlaceCursorAtEnd`.
 *
 * @param state The text and selection
 * @param label Floating label text. Null renders no label (the default for [ListenUpTextFieldVariant.Hero]).
 * @param modifier Optional modifier
 * @param placeholder Hint text shown when empty
 * @param enabled Whether the field is enabled for input
 * @param isError Whether to show error styling
 * @param supportingText Helper or error text below field
 * @param obfuscation Non-null makes this a password field: [TextObfuscationMode.Hidden] masks the
 *   text, [TextObfuscationMode.Visible] reveals it (drive it from the reveal toggle). Null is an
 *   ordinary field
 * @param keyboardOptions Keyboard type and IME action configuration
 * @param onKeyboardAction Called when the IME action key is pressed; null keeps the default action
 * @param leadingIcon Optional icon shown at the start of the field
 * @param trailingIcon Optional icon shown at the end of the field
 * @param onTrailingClick When non-null, the trailing icon becomes a clickable button
 *   (e.g. a password visibility toggle)
 * @param trailingIconContentDescription Accessibility label for the trailing icon BUTTON.
 *   Required in practice whenever [onTrailingClick] is non-null: without it the reveal /
 *   clear affordance is an unlabelled button to a screen reader. Ignored when
 *   [onTrailingClick] is null, because a non-interactive trailing icon is decorative and
 *   must stay unlabelled.
 * @param variant [ListenUpTextFieldVariant.Standard] (default) or [ListenUpTextFieldVariant.Hero]
 * @param heroContainerColor Tint for the [ListenUpTextFieldVariant.Hero] translucent container.
 *   Ignored by [ListenUpTextFieldVariant.Standard]. Defaults to the theme surface.
 * @param textStyle Input text style override. Null uses the [variant]'s default style
 * @param placeholderStyle Placeholder text style (including color) override. Null uses the
 *   [variant]'s default placeholder rendering
 * @param colors Field colors override. Null uses the [variant]'s default colors
 * @param shape Container shape override. Null uses [MaterialTheme.shapes.medium]
 * @param singleLine Whether the field is restricted to a single line of text (ignored by password
 *   fields, which are always single-line)
 * @param trailingContent Free-form trailing slot (e.g. a stateful action button). When non-null it
 *   wins over [trailingIcon] and [onTrailingClick]
 * @param inputTransformation THE sanctioned way to restrict or normalise input (digit-only fields,
 *   code alphabets, length caps) — see [transformedBy] and [limitedTo]. It runs inside the field, so
 *   the text the screen forwards is already transformed
 */
@Composable
fun ListenUpTextField(
    state: TextFieldState,
    label: String? = null,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    enabled: Boolean = true,
    isError: Boolean = false,
    supportingText: String? = null,
    obfuscation: TextObfuscationMode? = null,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    onKeyboardAction: (() -> Unit)? = null,
    leadingIcon: ImageVector? = null,
    trailingIcon: ImageVector? = null,
    onTrailingClick: (() -> Unit)? = null,
    trailingIconContentDescription: String? = null,
    variant: ListenUpTextFieldVariant = ListenUpTextFieldVariant.Standard,
    heroContainerColor: Color = MaterialTheme.colorScheme.surface,
    textStyle: TextStyle? = null,
    placeholderStyle: TextStyle? = null,
    colors: TextFieldColors? = null,
    shape: Shape? = null,
    singleLine: Boolean = true,
    trailingContent: (@Composable () -> Unit)? = null,
    inputTransformation: InputTransformation? = null,
) {
    val haptics = LocalHaptics.current
    val isHero = variant == ListenUpTextFieldVariant.Hero
    val heroTextStyle =
        MaterialTheme.typography.headlineSmall.copy(
            fontFamily = DisplayFontFamily,
            fontWeight = FontWeight.Bold,
        )
    val textStyleOrDefault = textStyle ?: if (isHero) heroTextStyle else LocalTextStyle.current
    val labelSlot: (@Composable TextFieldLabelScope.() -> Unit)? = label?.let { { Text(it) } }
    val placeholderSlot: (@Composable () -> Unit)? =
        placeholder?.let { placeholderText ->
            {
                when {
                    placeholderStyle != null -> {
                        Text(text = placeholderText, style = placeholderStyle)
                    }

                    isHero -> {
                        Text(
                            text = placeholderText,
                            style = heroTextStyle,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    else -> {
                        Text(placeholderText)
                    }
                }
            }
        }
    val supportingSlot: (@Composable () -> Unit)? = supportingText?.let { { Text(it) } }
    val leadingSlot: (@Composable () -> Unit)? = leadingIcon?.let { icon -> { Icon(icon, contentDescription = null) } }
    val trailingSlot: (@Composable () -> Unit)? =
        trailingContent
            ?: trailingIcon?.let { icon ->
                {
                    if (onTrailingClick != null) {
                        // The trailing affordance is a button (reveal password, clear, …), not
                        // text entry — so it answers back even though the field itself must not.
                        IconButton(
                            onClick = {
                                haptics.press()
                                onTrailingClick()
                            },
                        ) { Icon(icon, contentDescription = trailingIconContentDescription) }
                    } else {
                        Icon(icon, contentDescription = null)
                    }
                }
            }
    val fieldColors =
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
            }
    val actionHandler = onKeyboardAction?.let { action -> KeyboardActionHandler { action() } }
    val fieldShape = shape ?: MaterialTheme.shapes.medium
    val fieldModifier = modifier.fillMaxWidth()

    if (obfuscation != null) {
        OutlinedSecureTextField(
            state = state,
            inputTransformation = inputTransformation,
            textObfuscationMode = obfuscation,
            label = labelSlot,
            placeholder = placeholderSlot,
            textStyle = textStyleOrDefault,
            enabled = enabled,
            isError = isError,
            supportingText = supportingSlot,
            keyboardOptions = keyboardOptions,
            onKeyboardAction = actionHandler,
            leadingIcon = leadingSlot,
            trailingIcon = trailingSlot,
            colors = fieldColors,
            shape = fieldShape,
            modifier = fieldModifier,
        )
    } else {
        OutlinedTextField(
            state = state,
            inputTransformation = inputTransformation,
            label = labelSlot,
            placeholder = placeholderSlot,
            textStyle = textStyleOrDefault,
            enabled = enabled,
            isError = isError,
            supportingText = supportingSlot,
            keyboardOptions = keyboardOptions,
            onKeyboardAction = actionHandler,
            leadingIcon = leadingSlot,
            trailingIcon = trailingSlot,
            colors = fieldColors,
            lineLimits = if (singleLine) TextFieldLineLimits.SingleLine else TextFieldLineLimits.Default,
            shape = fieldShape,
            modifier = fieldModifier,
        )
    }
}

/**
 * The accessibility label for a password field's reveal toggle, given whether the password is
 * currently visible. One shape for all six password fields (sign-in, sign-up ×2, root reset,
 * forgot-password, edit-profile), so the announced label cannot drift between them.
 */
@Composable
internal fun passwordVisibilityDescription(visible: Boolean): String =
    if (visible) {
        stringResource(Res.string.common_hide_password)
    } else {
        stringResource(Res.string.common_show_password)
    }
