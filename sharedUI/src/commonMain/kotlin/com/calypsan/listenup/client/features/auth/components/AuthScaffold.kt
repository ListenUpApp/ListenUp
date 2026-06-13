package com.calypsan.listenup.client.features.auth.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.calypsan.listenup.client.design.TwoPaneMinWidth
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.auth_hero_tagline
import listenup.composeapp.generated.resources.brand_mark
import listenup.composeapp.generated.resources.common_back
import listenup.composeapp.generated.resources.common_listenup

/** A tertiary-container pill shown in the hero (e.g. "Server administrator"). */
data class AuthBadge(
    val icon: ImageVector,
    val label: String,
)

/** The form column never grows wider than this — keeps text line-length comfortable on big windows. */
private val FormMaxWidth = 460.dp

/**
 * Adaptive **and** responsive shell for every auth screen.
 *
 * The layout is chosen from the *actual available width* (via [BoxWithConstraints]), not just the
 * global window size class — so it behaves on resizable desktop windows, Android desktop mode,
 * foldables, and embedded panes:
 * - **< [TwoPaneMinWidth]** → a color-blocked [primaryContainer] hero (brand mark, display title,
 *   subtitle, optional badge, optional back) with the form scrolling beneath it. The form is
 *   centered and width-capped so a wide single-column window never stretches it edge-to-edge.
 * - **≥ [TwoPaneMinWidth]** → a split layout: a constrained brand panel on the left and the centered,
 *   width-capped form on the right. Both panes flex with the window.
 *
 * Screens supply only their [content] (fields + actions); the chrome is identical everywhere.
 */
@Composable
fun AuthScaffold(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    badge: AuthBadge? = null,
    onBack: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        BoxWithConstraints {
            if (maxWidth >= TwoPaneMinWidth) {
                AuthSplitLayout(maxWidth, title, subtitle, badge, onBack, content)
            } else {
                AuthHeroLayout(title, subtitle, badge, onBack, content)
            }
        }
    }
}

@Composable
private fun AuthHeroLayout(
    title: String,
    subtitle: String?,
    badge: AuthBadge?,
    onBack: (() -> Unit)?,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(bottomStart = 44.dp, bottomEnd = 44.dp))
                    .background(MaterialTheme.colorScheme.primaryContainer),
        ) {
            HeroBlobs()
            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .systemBarsPadding()
                        .padding(start = 26.dp, end = 26.dp, top = 16.dp, bottom = 36.dp),
            ) {
                BrandRow(onBack = onBack, onColor = true)
                Spacer(Modifier.height(30.dp))
                AuthTitleBlock(title, subtitle, badge, onColor = true)
            }
        }
        // Form — centered + capped so wide single-column windows stay readable.
        Column(
            modifier =
                Modifier
                    .widthIn(max = FormMaxWidth)
                    .fillMaxWidth()
                    .padding(horizontal = 26.dp, vertical = 28.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
            content = content,
        )
    }
}

@Composable
private fun AuthSplitLayout(
    availableWidth: Dp,
    title: String,
    subtitle: String?,
    badge: AuthBadge?,
    onBack: (() -> Unit)?,
    content: @Composable ColumnScope.() -> Unit,
) {
    // Brand panel takes ~42% of the window, clamped so it neither crushes the form nor sprawls.
    val brandWidth = (availableWidth * 0.42f).coerceIn(360.dp, 560.dp)
    Row(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier =
                Modifier
                    .fillMaxHeight()
                    .widthIn(max = brandWidth)
                    .width(brandWidth)
                    .background(MaterialTheme.colorScheme.primaryContainer),
        ) {
            HeroBlobs()
            Column(
                modifier = Modifier.fillMaxSize().systemBarsPadding().padding(56.dp),
                verticalArrangement = Arrangement.Center,
            ) {
                BrandMark(onColor = true)
                Spacer(Modifier.height(32.dp))
                Text(
                    text = stringResource(Res.string.auth_hero_tagline),
                    style = MaterialTheme.typography.displaySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                Spacer(Modifier.height(16.dp))
                Text(
                    text =
                        "Stream or download from your own ListenUp server — pick up on any device, " +
                            "right where you left off.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.82f),
                    modifier = Modifier.widthIn(max = 360.dp),
                )
            }
        }
        Box(
            modifier = Modifier.fillMaxHeight().weight(1f),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                modifier =
                    Modifier
                        .systemBarsPadding()
                        .imePadding()
                        .padding(48.dp)
                        .widthIn(max = FormMaxWidth)
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
            ) {
                if (onBack != null) {
                    BackButton(onBack)
                    Spacer(Modifier.height(20.dp))
                }
                AuthTitleBlock(title, subtitle, badge, onColor = false)
                Spacer(Modifier.height(28.dp))
                Column(verticalArrangement = Arrangement.spacedBy(20.dp), content = content)
            }
        }
    }
}

@Composable
private fun AuthTitleBlock(
    title: String,
    subtitle: String?,
    badge: AuthBadge?,
    onColor: Boolean,
) {
    val ink = if (onColor) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
    val muted =
        if (onColor) {
            MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.82f)
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (badge != null) {
            AssistChip(
                onClick = {},
                enabled = false,
                label = { Text(badge.label) },
                leadingIcon = { Icon(badge.icon, contentDescription = null, modifier = Modifier.size(18.dp)) },
                colors =
                    AssistChipDefaults.assistChipColors(
                        disabledContainerColor = MaterialTheme.colorScheme.tertiaryContainer,
                        disabledLabelColor = MaterialTheme.colorScheme.onTertiaryContainer,
                        disabledLeadingIconContentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                    ),
                border = null,
            )
        }
        Text(text = title, style = MaterialTheme.typography.displaySmall, color = ink)
        if (subtitle != null) {
            Text(text = subtitle, style = MaterialTheme.typography.bodyLarge, color = muted)
        }
    }
}

@Composable
private fun BrandRow(
    onBack: (() -> Unit)?,
    onColor: Boolean,
) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        if (onBack != null) BackButton(onBack)
        BrandMark(onColor = onColor)
    }
}

@Composable
private fun BackButton(onBack: () -> Unit) {
    FilledIconButton(
        onClick = onBack,
        colors =
            IconButtonDefaults.filledIconButtonColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                contentColor = MaterialTheme.colorScheme.onSurface,
            ),
    ) {
        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(Res.string.common_back))
    }
}

/**
 * The ListenUp brand lockup — a coral rounded-square with a headphones glyph, plus the wordmark.
 * [onColor] picks the on-primary-container ink for use over the hero panel.
 */
@Composable
fun BrandMark(
    modifier: Modifier = Modifier,
    onColor: Boolean = false,
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier =
                Modifier
                    .size(52.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(MaterialTheme.colorScheme.primary),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = painterResource(Res.drawable.brand_mark),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.size(40.dp),
            )
        }
        Text(
            text = stringResource(Res.string.common_listenup),
            style = MaterialTheme.typography.headlineSmall,
            color = if (onColor) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
        )
    }
}

/** Decorative soft shapes echoing the design's blob hero. Purely cosmetic. */
@Composable
private fun HeroBlobs() {
    Box(modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(bottomStart = 44.dp, bottomEnd = 44.dp))) {
        Box(
            modifier =
                Modifier
                    .size(230.dp)
                    .offset(x = 300.dp, y = (-60).dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)),
        )
        Box(
            modifier =
                Modifier
                    .size(170.dp)
                    .offset(x = (-50).dp, y = 120.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.40f)),
        )
    }
}

/**
 * A muted helper card (icon + body) used under fields — e.g. the "your server address looks
 * like…" tip on the connect screen.
 */
@Composable
fun AuthHelperCard(
    icon: ImageVector,
    text: String,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Row(modifier = Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(
                icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.tertiary,
                modifier = Modifier.size(22.dp),
            )
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
