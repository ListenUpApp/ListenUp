package com.calypsan.listenup.client.design.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.calypsan.listenup.client.design.haptics.Haptics
import com.calypsan.listenup.client.design.haptics.LocalHaptics
import com.calypsan.listenup.client.design.theme.Spacing

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
 * @param title Primary label, [MaterialTheme.typography.titleMedium].
 * @param modifier Modifier for the row.
 * @param subtitle Optional secondary description in [onSurfaceVariant] body text; wraps to as many
 *   lines as it needs so it never truncates on narrow (phone) widths.
 * @param icon Optional leading glyph; when null (and [leading] is null) the row has no leading tile.
 * @param accent Accent colour for the leading tile.
 * @param danger When true, uses the error-tinted tile and an error-coloured title.
 * @param onClick Optional tap handler; when set the whole row is one button.
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
                    Modifier.clickable(role = Role.Button) {
                        haptics.press()
                        onClick()
                    }
                } else {
                    Modifier
                },
            )
    Row(
        modifier = rowModifier.padding(horizontal = Spacing.lg, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        if (leading != null) {
            leading()
        } else {
            icon?.let { TonalIconTile(icon = it, accent = accent, danger = danger) }
        }
        Column(modifier = Modifier.weight(1f)) {
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
        trailing?.invoke()
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
