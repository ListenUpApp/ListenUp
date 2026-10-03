package com.calypsan.listenup.client.design.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import com.calypsan.listenup.client.design.haptics.Haptics
import com.calypsan.listenup.client.design.haptics.LocalHaptics
import com.calypsan.listenup.client.design.theme.Spacing
import com.calypsan.listenup.client.design.util.isLargeFontScale

/**
 * One segment of a [SectionGroup] (or a bare [SegmentedGroup]): the row paints its own tonal segment,
 * so consecutive rows read as Material 3 Expressive's segmented list — separated by the group's gap,
 * never by a hairline. It carries an optional leading [TonalIconTile], a [title] (+ optional
 * [subtitle]) text column, and an optional [trailing] control slot. Settings entries (selector
 * pills, toggles, info values, navigation) and Admin rows (users, actions) all compose this.
 *
 * A navigation row is just a row with an [onClick] and no [trailing]: Material has no disclosure
 * chevron, so the whole row being a named button is the affordance. When [danger] is set, the
 * leading tile switches to its error variant and the [title] renders in the error colour — the
 * destructive variant for sign-out-style rows.
 *
 * The leading slot has two paths: by default the [icon] renders inside a [TonalIconTile] — kept on
 * purpose, as Android's own settings do, because a coloured glyph lets a long page be scanned by
 * shape before it is read; when a [leading] composable is supplied it REPLACES the icon tile entirely
 * (so e.g. user rows can supply a [UserAvatar]). [icon] is ignored when [leading] is non-null.
 *
 * The text comes first. The [trailing] control sits beside the text only while it leaves the text room
 * for its longest word — and, at a large font, while it takes no more than a modest share of the row;
 * otherwise it moves beneath the text, so a large font never squeezes the title into a column of letters.
 * At a large font the decorative [icon] tile steps aside too, and the text takes its width.
 *
 * @param title Primary label, [MaterialTheme.typography.titleMedium].
 * @param modifier Modifier for the row.
 * @param subtitle Optional secondary description in [onSurfaceVariant] body text; wraps to as many
 *   lines as it needs so it never truncates on narrow (phone) widths.
 * @param icon Optional leading glyph; when null (and [leading] is null) the row has no leading tile.
 * @param accent Accent colour for the leading tile.
 * @param danger When true, uses the error-tinted tile and an error-coloured title.
 * @param onClick Optional tap handler; when set the whole row is one button.
 * @param onClickLabel What a tap does, when the title alone doesn't say — e.g. "Open in browser" for a
 *   row that leaves the app. TalkBack reads it as "double-tap to …".
 * @param leading Optional custom leading slot; when set it replaces the [icon] tile (e.g. an avatar).
 * @param trailing Optional trailing control (pill, switch, value text) — never a disclosure chevron.
 */
@Composable
fun SettingRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    icon: ImageVector? = null,
    accent: Color = MaterialTheme.colorScheme.primary,
    danger: Boolean = false,
    onClick: (() -> Unit)? = null,
    onClickLabel: String? = null,
    leading: @Composable (() -> Unit)? = null,
    trailing: @Composable (() -> Unit)? = null,
) {
    val haptics = LocalHaptics.current
    val rowModifier =
        modifier
            .fillMaxWidth()
            .clip(SegmentShape)
            .background(segmentColor)
            .then(
                if (onClick != null) {
                    Modifier.clickable(onClickLabel = onClickLabel, role = Role.Button) {
                        haptics.press()
                        onClick()
                    }
                } else {
                    Modifier
                },
            )
    val leadingSlot: (@Composable () -> Unit)? =
        when {
            leading != null -> {
                leading
            }

            icon != null && !isLargeFontScale() -> {
                { TonalIconTile(icon = icon, accent = accent, danger = danger) }
            }

            else -> {
                null
            }
        }
    SettingRowLayout(
        modifier = rowModifier.padding(horizontal = Spacing.lg, vertical = 14.dp),
        trailingShare = if (isLargeFontScale()) LARGE_TEXT_TRAILING_SHARE else 1f,
        leading = leadingSlot,
        trailing = trailing,
    ) {
        Column {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color =
                    if (danger) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
            )
            subtitle?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** The gap between a row's leading tile, its text and its trailing control. */
private val ROW_GAP = 14.dp

/** The gap above a trailing control that has moved beneath the text. */
private val STACKED_GAP = Spacing.md

/** At a large font, the most of a row's text width a trailing control may take and still sit beside the text. */
private const val LARGE_TEXT_TRAILING_SHARE = 0.4f

/**
 * Lays a row out text-first. [trailing] sits beside [text] when it takes at most [trailingShare] of the room
 * after [leading] and leaves [text] at least its longest word; otherwise it drops beneath [text], aligned
 * with it, and [text] takes the full width. Everything is centred vertically when side by side.
 */
@Composable
private fun SettingRowLayout(
    modifier: Modifier,
    trailingShare: Float,
    leading: (@Composable () -> Unit)?,
    trailing: (@Composable () -> Unit)?,
    text: @Composable () -> Unit,
) {
    Layout(
        contents = listOf(leading ?: {}, text, trailing ?: {}),
        modifier = modifier,
    ) { (leadingMeasurables, textMeasurables, trailingMeasurables), constraints ->
        val gap = ROW_GAP.roundToPx()
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val width = constraints.maxWidth
        val leadingPlaceable = leadingMeasurables.firstOrNull()?.measure(loose)
        val textStart = leadingPlaceable?.let { it.width + gap } ?: 0
        val textRoom = (width - textStart).coerceAtLeast(0)
        val textMeasurable = textMeasurables.single()
        val trailingMeasurable = trailingMeasurables.firstOrNull()

        val fitsBeside =
            trailingMeasurable == null ||
                trailingMeasurable.maxIntrinsicWidth(Constraints.Infinity).let { trailingWidth ->
                    trailingWidth <= textRoom * trailingShare &&
                        textRoom - trailingWidth - gap >= textMeasurable.minIntrinsicWidth(Constraints.Infinity)
                }

        if (trailingMeasurable != null && !fitsBeside) {
            val stackedGap = STACKED_GAP.roundToPx()
            val textPlaceable = textMeasurable.measure(Constraints.fixedWidth(textRoom))
            val trailingPlaceable = trailingMeasurable.measure(loose.copy(maxWidth = textRoom))
            val textBlock = textPlaceable.height + stackedGap + trailingPlaceable.height
            val height = maxOf(leadingPlaceable?.height ?: 0, textBlock).coerceAtLeast(constraints.minHeight)
            layout(width, height) {
                leadingPlaceable?.placeRelative(
                    0,
                    ((textPlaceable.height - leadingPlaceable.height) / 2).coerceAtLeast(0),
                )
                textPlaceable.placeRelative(textStart, 0)
                trailingPlaceable.placeRelative(textStart, textPlaceable.height + stackedGap)
            }
        } else {
            val trailingPlaceable = trailingMeasurable?.measure(loose.copy(maxWidth = textRoom))
            val trailingSpace = trailingPlaceable?.let { it.width + gap } ?: 0
            val textWidth = (textRoom - trailingSpace).coerceAtLeast(0)
            val textPlaceable = textMeasurable.measure(Constraints.fixedWidth(textWidth))
            val height =
                maxOf(leadingPlaceable?.height ?: 0, textPlaceable.height, trailingPlaceable?.height ?: 0)
                    .coerceAtLeast(constraints.minHeight)
            layout(width, height) {
                leadingPlaceable?.placeRelative(0, (height - leadingPlaceable.height) / 2)
                textPlaceable.placeRelative(textStart, (height - textPlaceable.height) / 2)
                trailingPlaceable?.placeRelative(
                    width - trailingPlaceable.width,
                    (height - trailingPlaceable.height) / 2,
                )
            }
        }
    }
}

/**
 * A [SettingRow] that opens another screen. The whole row is one [Role.Button] named by its
 * [title], with a press haptic — and deliberately nothing trailing it: Material has no disclosure
 * chevron (that is an iOS table idiom), so the title and subtitle get the row's full width.
 *
 * @param title Primary label; also the button's accessible name.
 * @param onClick Opens the destination.
 * @param modifier Modifier for the row.
 * @param subtitle Optional description of the destination.
 * @param icon Optional leading glyph, rendered in a [TonalIconTile].
 * @param accent Accent colour for the leading tile.
 */
@Composable
fun SettingNavigationRow(
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    icon: ImageVector? = null,
    accent: Color = MaterialTheme.colorScheme.primary,
) {
    SettingRow(
        title = title,
        subtitle = subtitle,
        icon = icon,
        accent = accent,
        onClick = onClick,
        modifier = modifier,
    )
}

/**
 * A [SettingRow] that owns a [Switch] — the canonical on/off setting. The WHOLE row is the switch:
 * one TalkBack node with [Role.Switch], named by [title] (and [subtitle]), announcing On/Off, and a
 * tap anywhere on the row flips it with a toggle haptic. The [Switch] itself is drawn with
 * `onCheckedChange = null`, so it is purely the visual — a second, nameless "On, switch" node is
 * exactly what this primitive exists to prevent.
 *
 * @param title Primary label; also the switch's accessible name.
 * @param checked Whether the setting is on.
 * @param onCheckedChange Invoked with the new value when the row is toggled.
 * @param modifier Modifier for the row.
 * @param subtitle Optional description of what the switch does.
 * @param icon Optional leading glyph, rendered in a [TonalIconTile].
 * @param accent Accent colour for the leading tile.
 * @param enabled When false, the row is announced as disabled and cannot be toggled.
 */
@Composable
fun SettingToggleRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    icon: ImageVector? = null,
    accent: Color = MaterialTheme.colorScheme.primary,
    enabled: Boolean = true,
) {
    val haptics = LocalHaptics.current
    SettingRow(
        title = title,
        subtitle = subtitle,
        icon = icon,
        accent = accent,
        modifier = modifier.switchRow(checked = checked, haptics = haptics, enabled = enabled, onCheckedChange),
    ) {
        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}

/**
 * Makes a whole row the switch: [Role.Switch] semantics merged over the row's label, On/Off state,
 * and a toggle haptic. [SettingToggleRow] is built on it; a bespoke row that cannot take
 * [SettingToggleRow]'s chrome applies it directly and draws its `Switch` with
 * `onCheckedChange = null`, so there is still one switch shape across the app.
 *
 * @param checked Whether the switch is on.
 * @param haptics The semantic haptics to fire on toggle (`LocalHaptics.current`).
 * @param enabled When false, the row cannot be toggled and is announced as disabled.
 * @param onCheckedChange Invoked with the new value.
 */
fun Modifier.switchRow(
    checked: Boolean,
    haptics: Haptics,
    enabled: Boolean = true,
    onCheckedChange: (Boolean) -> Unit,
): Modifier =
    toggleable(value = checked, enabled = enabled, role = Role.Switch) { on ->
        haptics.toggle(on = on)
        onCheckedChange(on)
    }
