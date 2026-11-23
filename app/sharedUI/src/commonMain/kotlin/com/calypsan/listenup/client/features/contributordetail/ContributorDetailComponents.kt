package com.calypsan.listenup.client.features.contributordetail

import com.calypsan.listenup.client.design.icons.ListenUpIcons
import com.calypsan.listenup.client.design.text.forProse
import com.calypsan.listenup.api.dto.image.ImageFrame
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import listenup.composeapp.generated.resources.edit_adjust_photo
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.calypsan.listenup.client.design.haptics.LocalHaptics
import com.calypsan.listenup.client.design.LocalDeviceContext
import com.calypsan.listenup.client.design.components.HeroNavRow
import com.calypsan.listenup.client.design.components.cookieScallopShape
import com.calypsan.listenup.client.design.theme.DisplayFontFamily
import com.calypsan.listenup.client.design.transitions.contributorHeroKey
import com.calypsan.listenup.client.design.transitions.heroElement
import com.calypsan.listenup.client.design.theme.Spacing
import com.calypsan.listenup.client.features.contributoredit.components.ContributorColorScheme
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.book_detail_more_options
import listenup.composeapp.generated.resources.common_about
import listenup.composeapp.generated.resources.common_delete
import listenup.composeapp.generated.resources.common_edit
import listenup.composeapp.generated.resources.common_read_less
import listenup.composeapp.generated.resources.common_read_more
import listenup.composeapp.generated.resources.match_menu_item
import listenup.composeapp.generated.resources.contributor_name_profile_image
import org.jetbrains.compose.resources.stringResource
import com.calypsan.listenup.client.design.theme.ContentShapes
import com.calypsan.listenup.client.design.theme.HeroInk
// =============================================================================
// SHARED COMPONENTS
// =============================================================================

/**
 * Floating back button and overflow menu. The menu offers only what the user may do — Edit, Match
 * details and (when there is a photo, [onAdjustPhoto] non-null) Adjust photo under Edit metadata,
 * Delete under Curate library — and is absent when that is nothing.
 */
@Composable
internal fun NavigationBar(
    onBackClick: () -> Unit,
    onEditClick: () -> Unit,
    onMatchDetails: () -> Unit,
    onAdjustPhoto: (() -> Unit)?,
    onDeleteClick: () -> Unit,
    canEditMetadata: Boolean,
    canCurateLibrary: Boolean,
    applyStatusBarInset: Boolean = true,
    actionsEnabled: Boolean = true,
) {
    HeroNavRow(
        onBack = onBackClick,
        applyStatusBarInset = applyStatusBarInset,
    ) {
        if (!LocalDeviceContext.current.isLeanback && (canEditMetadata || canCurateLibrary)) {
            OverflowMenu(
                onEditClick = onEditClick,
                onMatchDetails = onMatchDetails,
                onAdjustPhoto = onAdjustPhoto,
                onDeleteClick = onDeleteClick,
                canEditMetadata = canEditMetadata,
                canCurateLibrary = canCurateLibrary,
                actionsEnabled = actionsEnabled,
            )
        }
    }
}

/**
 * Overflow menu (edit / Match details / delete) shared by both hero navigation rows.
 */
@Composable
private fun OverflowMenu(
    onEditClick: () -> Unit,
    onMatchDetails: () -> Unit,
    onAdjustPhoto: (() -> Unit)?,
    onDeleteClick: () -> Unit,
    canEditMetadata: Boolean,
    canCurateLibrary: Boolean,
    actionsEnabled: Boolean = true,
) {
    val haptics = LocalHaptics.current
    var showMenu by remember { mutableStateOf(false) }

    Box {
        IconButton(
            onClick = {
                haptics.press()
                showMenu = true
            },
            modifier =
                Modifier
                    .size(48.dp)
                    .background(
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                        shape = CircleShape,
                    ),
        ) {
            Icon(
                imageVector = ListenUpIcons.MoreVertFilled,
                contentDescription = stringResource(Res.string.book_detail_more_options),
                tint = MaterialTheme.colorScheme.onSurface,
            )
        }

        DropdownMenu(
            expanded = showMenu,
            onDismissRequest = { showMenu = false },
        ) {
            if (canEditMetadata) {
                DropdownMenuItem(
                    text = { Text(stringResource(Res.string.common_edit)) },
                    leadingIcon = { Icon(ListenUpIcons.EditFilled, null) },
                    onClick = {
                        haptics.press()
                        showMenu = false
                        onEditClick()
                    },
                    enabled = actionsEnabled,
                )
                DropdownMenuItem(
                    text = { Text(stringResource(Res.string.match_menu_item)) },
                    leadingIcon = { Icon(ListenUpIcons.SearchFilled, null) },
                    onClick = {
                        haptics.press()
                        showMenu = false
                        onMatchDetails()
                    },
                    enabled = actionsEnabled,
                )
                if (onAdjustPhoto != null) {
                    DropdownMenuItem(
                        text = { Text(stringResource(Res.string.edit_adjust_photo)) },
                        leadingIcon = { Icon(ListenUpIcons.CropFilled, null) },
                        onClick = {
                            haptics.press()
                            showMenu = false
                            onAdjustPhoto()
                        },
                        enabled = actionsEnabled,
                    )
                }
            }
            if (canCurateLibrary) {
                if (canEditMetadata) HorizontalDivider()
                DropdownMenuItem(
                    text = {
                        Text(
                            stringResource(Res.string.common_delete),
                            color = MaterialTheme.colorScheme.error,
                        )
                    },
                    leadingIcon = {
                        Icon(
                            ListenUpIcons.DeleteFilled,
                            null,
                            tint = MaterialTheme.colorScheme.error,
                        )
                    },
                    onClick = {
                        haptics.press()
                        showMenu = false
                        onDeleteClick()
                    },
                    enabled = actionsEnabled,
                )
            }
        }
    }
}
/**
 * Elevated avatar (140dp) with contributor image or initials.
 *
 * Hero call sites pass the M3 Expressive cookie [shape]; smaller inline uses keep the default circle.
 */
@Composable
private fun ElevatedAvatar(
    name: String,
    imagePath: String?,
    frame: ImageFrame?,
    contributorId: String,
    colorScheme: ContributorColorScheme,
    shape: Shape = CircleShape,
    elevation: Dp = 12.dp,
) {
    val initials =
        if (name.isNotBlank()) {
            name
                .trim()
                .split("\\s+".toRegex())
                .let { parts ->
                    when {
                        parts.size >= 2 -> "${parts[0].first()}${parts[1].first()}"
                        name.length >= 2 -> name.take(2)
                        else -> name.take(1)
                    }
                }.uppercase()
        } else {
            "?"
        }

    ElevatedCard(
        shape = shape,
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = elevation),
        colors =
            CardDefaults.elevatedCardColors(
                containerColor = colorScheme.primary,
            ),
        // The hero is the whole avatar — see the note on the Contributors-list half. The photo alone
        // draws nothing for a contributor without one, which is most of them.
        modifier = Modifier.size(140.dp).heroElement(contributorHeroKey(contributorId), shape),
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            var imageLoaded by remember(contributorId) { mutableStateOf(false) }

            if (!imageLoaded) {
                Text(
                    text = initials,
                    style =
                        MaterialTheme.typography.displayMedium.copy(
                            fontFamily = DisplayFontFamily,
                            fontWeight = FontWeight.Bold,
                        ),
                    color = colorScheme.onPrimary,
                )
            }

            com.calypsan.listenup.client.design.components.ContributorCoverImage(
                contributorId = contributorId,
                imagePath = imagePath,
                contentDescription = stringResource(Res.string.contributor_name_profile_image, name),
                frame = frame,
                modifier = Modifier.fillMaxSize(),
                onState = { state ->
                    if (state is coil3.compose.AsyncImagePainter.State.Success) {
                        imageLoaded = true
                    }
                },
            )
        }
    }
}

@Composable
internal fun BiographySection(
    description: String,
    isExpanded: Boolean,
    onToggleExpanded: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptics = LocalHaptics.current
    Column(modifier = modifier) {
        Text(
            text = stringResource(Res.string.common_about),
            style =
                MaterialTheme.typography.titleMedium.copy(
                    fontFamily = DisplayFontFamily,
                    fontWeight = FontWeight.Bold,
                ),
            modifier = Modifier.padding(bottom = 12.dp),
        )

        Text(
            text = description,
            style = MaterialTheme.typography.bodyLarge.forProse(),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = if (isExpanded) Int.MAX_VALUE else 4,
            overflow = TextOverflow.Ellipsis,
        )

        if (description.length > 200) {
            TextButton(
                onClick = {
                    haptics.press()
                    onToggleExpanded()
                },
                contentPadding = PaddingValues(0.dp),
            ) {
                Text(stringResource(if (isExpanded) Res.string.common_read_less else Res.string.common_read_more))
            }
        }
    }
}

/**
 * The contributor screen's first frame: the colour banner and the portrait, where the loaded screen
 * puts them.
 *
 * The portrait is the hero transition's landing target — without it the shared element leaving the
 * Contributors list has nothing to fly into and silently does nothing. Deliberately renders the bare
 * image rather than [ElevatedAvatar]: that card paints a filled surface and initials immediately,
 * which would sit here as a finished-looking avatar before the real one arrives.
 * [com.calypsan.listenup.client.design.components.ContributorCoverImage] resolves a portrait from
 * [contributorId] alone, so it needs nothing this screen has not got yet.
 */
@Composable
internal fun ContributorDetailLoadingFrame(
    contributorId: String,
    onBackClick: () -> Unit,
) {
    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(ContentShapes.hero)
                .background(MaterialTheme.colorScheme.primaryContainer),
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(bottom = 28.dp)) {
            NavigationBar(
                onBackClick = onBackClick,
                onEditClick = {},
                onMatchDetails = {},
                onAdjustPhoto = null,
                onDeleteClick = {},
                canEditMetadata = false,
                canCurateLibrary = false,
                // Stub callbacks while the contributor loads: disable the rows so none of
                // them confirms a tap that does nothing.
                actionsEnabled = false,
            )
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.screenMargin),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                // The rim RingedScallopAvatar draws, at the same 152/140 sizes, so the portrait
                // lands exactly where the loaded hero keeps it.
                Box(
                    modifier =
                        Modifier
                            .size(152.dp)
                            .clip(cookieScallopShape())
                            .background(MaterialTheme.colorScheme.primary),
                    contentAlignment = Alignment.Center,
                ) {
                    com.calypsan.listenup.client.design.components.ContributorCoverImage(
                        contributorId = contributorId,
                        imagePath = null,
                        contentDescription = null,
                        modifier =
                            Modifier
                                .size(140.dp)
                                .heroElement(contributorHeroKey(contributorId), cookieScallopShape()),
                    )
                }
            }
        }
    }
}

/** Scallop avatar with a brand-colored rim, matching the design's "cookie" avatar. */
@Composable
internal fun RingedScallopAvatar(
    name: String,
    imagePath: String?,
    frame: ImageFrame?,
    contributorId: String,
    colorScheme: ContributorColorScheme,
) {
    Box(
        modifier =
            Modifier
                .size(152.dp)
                .clip(cookieScallopShape())
                .background(MaterialTheme.colorScheme.primary),
        contentAlignment = Alignment.Center,
    ) {
        ElevatedAvatar(
            name = name,
            imagePath = imagePath,
            frame = frame,
            contributorId = contributorId,
            colorScheme = colorScheme,
            shape = cookieScallopShape(),
            elevation = 0.dp,
        )
    }
}

/** Small role pill (Author/Narrator), tinted per role to match the design. */
@Composable
internal fun RoleChip(label: String) {
    val narrator = label.equals("Narrator", ignoreCase = true)
    val bg = if (narrator) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.tertiaryContainer
    val fg = if (narrator) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onTertiaryContainer
    Box(
        modifier = Modifier.clip(CircleShape).background(bg).padding(horizontal = 14.dp, vertical = 6.dp),
    ) {
        Text(text = label, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = fg)
    }
}

/**
 * Stat pill (book count, total hours) for the hero. [onColor] tints it for placement on the
 * color-blocked hero; [icon] defaults to the book glyph so existing call sites are unchanged.
 */
@Composable
internal fun HeroStatChip(
    label: String,
    onColor: Boolean,
    icon: ImageVector = ListenUpIcons.MenuBookFilled,
) {
    val bg =
        if (onColor) {
            HeroInk.wash()
        } else {
            MaterialTheme.colorScheme.surfaceContainerHigh
        }
    val fg = if (onColor) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        modifier = Modifier.clip(CircleShape).background(bg).padding(horizontal = Spacing.lg, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(19.dp),
        )
        Text(text = label, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, color = fg)
    }
}

/** Soft organic accent blob behind the hero content (echoes the design's brand squircle). */
@Composable
internal fun HeroBlob(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.clip(BlobShape).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.13f)),
    )
}

/** Asymmetric rounded squircle approximating the design's organic blob. */
private val BlobShape =
    RoundedCornerShape(
        topStartPercent = 46,
        topEndPercent = 54,
        bottomEndPercent = 46,
        bottomStartPercent = 54,
    )

// =============================================================================
// HELPERS
// =============================================================================

/** Short role label for a hero chip (e.g. "author" -> "Author"). */
internal fun roleChipLabel(role: String): String =
    when (role.lowercase()) {
        "author" -> "Author"
        "narrator" -> "Narrator"
        "translator" -> "Translator"
        "editor" -> "Editor"
        else -> role.replaceFirstChar { it.uppercase() }
    }
