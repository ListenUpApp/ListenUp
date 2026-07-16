package com.calypsan.listenup.client.features.campfire

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Bespoke dark "warm glass over night" palette for the Campfire full-screen flow (Create, Invite,
 * Lobby, Room) — an approved divergence from the app's Material 3 baseline chrome (co-listening
 * design spec, 2026-07-11 lobby amendment). Every screen in the flow renders over
 * [CampfireBackdrop] regardless of the device's light/dark theme setting, so these colors are
 * literal constants rather than [androidx.compose.material3.MaterialTheme] roles — the fire is
 * always a night scene.
 */
internal object CampfireFlowColors {
    val NightTop = Color(0xFF07060D)
    val NightBottom = Color(0xFF14101A)
    val Glass = Color(0xFF16111C)
    val GlassBorder = Color(0x1FFFFFFF)
    val OnGlass = Color.White
    val OnGlassMuted = Color(0xB3FFFFFF)
    val OnGlassFaint = Color(0x80FFFFFF)
}

/** Max content width for the Campfire flow's single-column layout — responsive-not-stretched on tablet/desktop. */
internal val CampfireFlowContentMaxWidth = 480.dp

/**
 * The minimal book identity every Campfire flow screen renders (cover strip, now-playing strip) —
 * bundled so Create/Invite/Lobby/Room call sites don't repeat five loose parameters each.
 *
 * @property subtitle Narrator/author line shown under the title (already resolved by the caller —
 * see `BookDetailReadyContent`'s campfire wiring for how it falls back author-narrator).
 */
internal data class CampfireFlowBook(
    val bookId: String,
    val title: String,
    val subtitle: String,
    val coverPath: String?,
    val coverHash: String?,
    val coverBlurHash: String?,
)

/**
 * The flow's translucent "dark glass" card — the recurring container for the book strip, form
 * sections, and roster chrome across every Campfire flow screen.
 */
@Composable
internal fun CampfireGlassCard(
    modifier: Modifier = Modifier,
    shape: RoundedCornerShape = RoundedCornerShape(20.dp),
    content: @Composable () -> Unit,
) {
    Surface(
        modifier = modifier.clip(shape),
        shape = shape,
        color = CampfireFlowColors.Glass.copy(alpha = 0.62f),
        border = androidx.compose.foundation.BorderStroke(1.dp, CampfireFlowColors.GlassBorder),
        contentColor = CampfireFlowColors.OnGlass,
    ) {
        content()
    }
}

/** The flow's primary CTA — a coral-gradient pill, e.g. "Light the fire" / "Start listening together". */
@Composable
internal fun CampfireFireButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    isLoading: Boolean = false,
    leadingIcon: (@Composable () -> Unit)? = null,
) {
    val primary = MaterialTheme.colorScheme.primary
    val onPrimary = MaterialTheme.colorScheme.onPrimary
    val brush =
        if (enabled) {
            Brush.linearGradient(listOf(primary, primary))
        } else {
            Brush.linearGradient(listOf(Color(0x1FFFFFFF), Color(0x1FFFFFFF)))
        }
    Surface(
        onClick = onClick,
        enabled = enabled && !isLoading,
        modifier = modifier.heightIn(min = 56.dp),
        shape = CircleShape,
        color = Color.Transparent,
    ) {
        Box(
            modifier = Modifier.background(brush).heightIn(min = 56.dp),
            contentAlignment = Alignment.Center,
        ) {
            if (isLoading) {
                CircularProgressIndicator(modifier = Modifier.size(22.dp), color = onPrimary)
            } else {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
                ) {
                    leadingIcon?.invoke()
                    Text(
                        text = text,
                        color = if (enabled) onPrimary else CampfireFlowColors.OnGlassMuted,
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.5.sp,
                    )
                }
            }
        }
    }
}

/** Circular glass icon button — the back chevron and Room top-bar affordances. */
@Composable
internal fun CampfireGlassIconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
    icon: @Composable () -> Unit = {
        Icon(
            Icons.AutoMirrored.Filled.ArrowBack,
            contentDescription = contentDescription,
            tint = CampfireFlowColors.OnGlass,
        )
    },
) {
    Surface(
        onClick = onClick,
        modifier = modifier.size(40.dp),
        shape = CircleShape,
        color = CampfireFlowColors.Glass.copy(alpha = 0.55f),
        border = androidx.compose.foundation.BorderStroke(1.dp, CampfireFlowColors.GlassBorder),
    ) {
        Box(contentAlignment = Alignment.Center) { icon() }
    }
}

/** Shared header for the Create/Invite/Lobby screens — back chevron + eyebrow + title. */
@Composable
internal fun CampfireFlowHeader(
    eyebrow: String,
    title: String,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    trailing: @Composable (() -> Unit)? = null,
) {
    Row(
        modifier = modifier.widthIn(max = CampfireFlowContentMaxWidth).padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (onBack != null) {
            CampfireGlassIconButton(onClick = onBack)
        }
        androidx.compose.foundation.layout.Column(modifier = Modifier.weight(1f)) {
            Text(
                text = eyebrow,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.ExtraBold,
                fontSize = 11.5.sp,
                letterSpacing = 1.4.sp,
            )
            Text(
                text = title,
                color = CampfireFlowColors.OnGlass,
                fontWeight = FontWeight.ExtraBold,
                fontSize = 24.sp,
                textAlign = TextAlign.Start,
            )
        }
        trailing?.invoke()
    }
}

/** A section label ("Name", "Who can join") above a form group in the Campfire flow. */
@Composable
internal fun CampfireSectionLabel(
    text: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text = text,
        modifier = modifier.padding(horizontal = 4.dp),
        color = CampfireFlowColors.OnGlassMuted,
        fontWeight = FontWeight.Bold,
        fontSize = 11.5.sp,
        letterSpacing = 0.8.sp,
    )
}

/** A two/three-way segmented pill selector — "Who can join", control-mode style choices. */
@Composable
internal fun <T> CampfireSegmentedControl(
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    CampfireGlassCard(modifier = modifier, shape = RoundedCornerShape(14.dp)) {
        Row(
            modifier = Modifier.padding(5.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            options.forEach { (value, label) ->
                val isSelected = value == selected
                Surface(
                    onClick = { onSelect(value) },
                    modifier = Modifier.weight(1f).height(40.dp),
                    shape = RoundedCornerShape(10.dp),
                    color = if (isSelected) MaterialTheme.colorScheme.primary else Color.Transparent,
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            text = label,
                            color = if (isSelected) MaterialTheme.colorScheme.onPrimary else CampfireFlowColors.OnGlass,
                            fontWeight = if (isSelected) FontWeight.ExtraBold else FontWeight.Medium,
                            fontSize = 13.5.sp,
                        )
                    }
                }
            }
        }
    }
}

/** A toggle row inside a [CampfireGlassCard] — icon tile + title/subtitle + a coral switch. */
@Composable
internal fun CampfireToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.padding(vertical = 13.dp, horizontal = 4.dp).clickable { onCheckedChange(!checked) },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        androidx.compose.foundation.layout.Column(modifier = Modifier.weight(1f)) {
            Text(text = title, color = CampfireFlowColors.OnGlass, fontWeight = FontWeight.SemiBold, fontSize = 14.5.sp)
            Text(text = subtitle, color = CampfireFlowColors.OnGlassMuted, fontSize = 12.sp)
        }
        androidx.compose.material3.Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors =
                androidx.compose.material3.SwitchDefaults.colors(
                    checkedTrackColor = MaterialTheme.colorScheme.primary,
                    checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                    uncheckedTrackColor = Color(0x2EFFFFFF),
                    uncheckedThumbColor = CampfireFlowColors.OnGlass,
                ),
        )
    }
}
