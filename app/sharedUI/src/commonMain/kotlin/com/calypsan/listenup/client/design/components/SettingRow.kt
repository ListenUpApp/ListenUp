package com.calypsan.listenup.client.design.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.calypsan.listenup.client.design.haptics.Haptics
import com.calypsan.listenup.client.design.haptics.LocalHaptics

/** Leading-icon column width: tile (44) + row gap (14) ≈ the divider's inset start. */
private val DIVIDER_INSET = 70.dp

/**
 * A flat row meant to live INSIDE a [SectionGroup] card — it owns no surface of its own, only the
 * row chrome: an optional leading [TonalIconTile], a [title] (+ optional [subtitle]) text column,
 * and an optional [trailing] control slot. The canonical row for accent-headed grouped sections —
 * Settings entries (selector pills, toggles, info values, navigation chevrons) and Admin rows
 * (users, actions) all compose this.
 *
 * When [showDivider] is set, an inset [HorizontalDivider] (start-inset ~70.dp, clearing the leading
 * tile) is drawn at the top so consecutive rows read as a divided list. When [danger] is set, the
 * leading tile switches to its error variant and the [title] renders in the error colour — the
 * destructive variant for sign-out-style rows. Supplying [onClick] makes the whole row clickable.
 *
 * The leading slot has two paths: by default the [icon] renders inside a [TonalIconTile]; when a
 * [leading] composable is supplied it REPLACES the icon tile entirely (so e.g. user rows can supply
 * a [UserAvatar]). [icon] is ignored when [leading] is non-null.
 *
 * @param title Primary label, [MaterialTheme.typography.titleMedium].
 * @param modifier Modifier for the row.
 * @param subtitle Optional secondary description in [onSurfaceVariant] body text; wraps to as many
 *   lines as it needs so it never truncates on narrow (phone) widths.
 * @param icon Optional leading glyph; when null (and [leading] is null) the row has no leading tile.
 * @param accent Accent colour for the leading tile.
 * @param danger When true, uses the error-tinted tile and an error-coloured title.
 * @param showDivider When true, draws an inset divider above the row.
 * @param onClick Optional tap handler; when set the whole row is clickable.
 * @param leading Optional custom leading slot; when set it replaces the [icon] tile (e.g. an avatar).
 * @param trailing Optional trailing control (pill, switch, chevron, value text).
 */
@Composable
fun SettingRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    icon: ImageVector? = null,
    accent: Color = MaterialTheme.colorScheme.primary,
    danger: Boolean = false,
    showDivider: Boolean = false,
    onClick: (() -> Unit)? = null,
    leading: @Composable (() -> Unit)? = null,
    trailing: @Composable (() -> Unit)? = null,
) {
    val haptics = LocalHaptics.current
    val rowModifier =
        modifier
            .fillMaxWidth()
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
    Column(modifier = rowModifier) {
        if (showDivider) {
            HorizontalDivider(
                modifier = Modifier.padding(start = DIVIDER_INSET),
                color = MaterialTheme.colorScheme.outlineVariant,
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
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
 * @param showDivider When true, draws an inset divider above the row.
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
    showDivider: Boolean = false,
) {
    val haptics = LocalHaptics.current
    SettingRow(
        title = title,
        subtitle = subtitle,
        icon = icon,
        accent = accent,
        showDivider = showDivider,
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
