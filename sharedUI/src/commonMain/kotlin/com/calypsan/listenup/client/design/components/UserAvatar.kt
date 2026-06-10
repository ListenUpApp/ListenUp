@file:OptIn(ExperimentalUuidApi::class)

package com.calypsan.listenup.client.design.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.calypsan.listenup.client.domain.model.CachedUserProfile
import com.calypsan.listenup.client.domain.repository.ImageStorage
import com.calypsan.listenup.client.domain.repository.UserProfileRepository
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import org.koin.compose.koinInject

/**
 * Avatar render size variants used across the app.
 *
 * [fontSize] is expressed in sp but sized to match the circle diameter so that
 * initials never overflow the circle at large accessibility font scales.
 * (Internal rendering uses [LocalDensity] to derive a physical-pixel font size —
 * see [InitialsAvatar].)
 */
enum class AvatarSize(
    val dp: Dp,
    val fontSize: TextUnit,
) {
    Mini(20.dp, 9.sp),
    Small(32.dp, 12.sp),
    Medium(48.dp, 16.sp),
    Large(96.dp, 28.sp),
}

/**
 * The canonical avatar composable for the entire app.
 *
 * Resolves the user profile reactively via [UserProfileRepository.observeProfile] and
 * renders with a defensive cascade:
 *  1. Image avatar — loaded from local [ImageStorage] with disk + memory cache enabled.
 *     Falls back to an initials circle if the file has not yet been downloaded.
 *  2. Initials — uses [CachedUserProfile.avatarColor] (server-assigned hex, e.g. `"#3949AB"`)
 *     as the circle background. Initials are derived from [CachedUserProfile.displayName].
 *     Renders `"?"` when the name is blank.
 *  3. Loading placeholder — [MaterialTheme.colorScheme.surfaceVariant] circle shown while
 *     the profile is not yet in the local cache. Never silent grey.
 *
 * Fixes the legacy "every render = HTTP request" bug in [ClickableUserAvatar] by preferring
 * local storage and enabling Coil's memory and disk caches.
 *
 * @param userId Unique identifier for the user. Used to load the profile and as the Coil
 *   cache key for avatar images.
 * @param size Render size variant. Defaults to [AvatarSize.Medium].
 * @param modifier Applied to the outermost container.
 * @param onClick When non-null, wraps the avatar in a clickable modifier.
 */
@Composable
fun UserAvatar(
    userId: String,
    size: AvatarSize = AvatarSize.Medium,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
) {
    val repo: UserProfileRepository = koinInject()
    val profile by repo.observeProfile(userId).collectAsState(initial = null)

    val baseModifier =
        modifier
            .size(size.dp)
            .clip(CircleShape)
            .let { if (onClick != null) it.clickable(onClick = onClick) else it }

    when {
        profile != null -> ResolvedAvatar(profile = profile!!, userId = userId, size = size, modifier = baseModifier)
        else -> LoadingPlaceholder(modifier = baseModifier)
    }
}

// ---------------------------------------------------------------------------
// Internal rendering helpers
// ---------------------------------------------------------------------------

@Composable
private fun ResolvedAvatar(
    profile: CachedUserProfile,
    userId: String,
    size: AvatarSize,
    modifier: Modifier,
) {
    val imageStorage: ImageStorage = koinInject()
    val hasLocalAvatar = imageStorage.userAvatarExists(userId)

    if (profile.avatarType == "image" && hasLocalAvatar) {
        LocalImageAvatar(
            localPath = imageStorage.getUserAvatarPath(userId),
            cacheKey = "$userId-avatar",
            contentDescription = profile.displayName.ifBlank { "User avatar" },
            modifier = modifier,
        )
    } else {
        // "auto" type, or "image" type where the file has not downloaded yet.
        InitialsAvatar(
            userId = userId,
            displayName = profile.displayName,
            avatarColor = profile.avatarColor,
            size = size,
            modifier = modifier,
        )
    }
}

@Composable
private fun LocalImageAvatar(
    localPath: String,
    cacheKey: String,
    contentDescription: String,
    modifier: Modifier,
) {
    val context = LocalPlatformContext.current
    AsyncImage(
        model =
            ImageRequest
                .Builder(context)
                .data(localPath)
                .memoryCacheKey(cacheKey)
                .diskCacheKey(cacheKey)
                .memoryCachePolicy(CachePolicy.ENABLED)
                .diskCachePolicy(CachePolicy.ENABLED)
                .crossfade(true)
                .build(),
        contentDescription = contentDescription,
        modifier = modifier,
        contentScale = ContentScale.Crop,
    )
}

@Composable
private fun InitialsAvatar(
    userId: String,
    displayName: String,
    avatarColor: String,
    size: AvatarSize,
    modifier: Modifier,
) {
    val color = parseAvatarHexColor(avatarColor, userId)
    val initials =
        displayName
            .trim()
            .split("\\s+".toRegex())
            .filter { it.isNotBlank() }
            .take(2)
            .joinToString("") { it.first().uppercase() }
            .ifBlank { "?" }
    // Derive font size from physical pixels so the text stays inside the circle
    // regardless of the user's font-scale setting. ~38% of diameter is a good fit.
    val derivedFontSize = with(LocalDensity.current) { (size.dp.toPx() * 0.38f).toSp() }

    Box(
        modifier = modifier.background(color, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = initials,
            color = Color.White,
            fontSize = derivedFontSize,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun LoadingPlaceholder(modifier: Modifier) {
    Box(modifier = modifier.background(MaterialTheme.colorScheme.surfaceVariant, CircleShape))
}

// ---------------------------------------------------------------------------
// Internal utilities (no external callers — logic inlined at each call site)
// ---------------------------------------------------------------------------

// ---------------------------------------------------------------------------
// Internal utilities
// ---------------------------------------------------------------------------

/** Fully-opaque alpha channel OR-ed onto a parsed 24-bit RGB hex color. */
private const val OPAQUE_ALPHA_MASK = 0xFF000000L

/**
 * Parse a server-assigned hex color string (e.g. `"#6B7280"`) into a [Color].
 *
 * Falls back to [stableColorForUserId] when the hex string is blank or malformed, so every
 * initials avatar gets a visually distinct, cross-platform stable color rather than a
 * hardcoded neutral grey.
 *
 * @param hexColor Server-assigned hex color, e.g. `"#3949AB"`. May be blank or invalid for
 *   profiles created before the server began assigning colors.
 * @param userId Forwarded to [stableColorForUserId] when [hexColor] cannot be parsed.
 */
private fun parseAvatarHexColor(
    hexColor: String,
    userId: String,
): Color =
    try {
        if (hexColor.isBlank()) error("blank")
        Color(hexColor.removePrefix("#").toLong(16) or OPAQUE_ALPHA_MASK)
    } catch (_: Exception) {
        stableColorForUserId(userId)
    }

/**
 * Stable avatar color derived from [userId] using a cross-platform stable UUID hash.
 *
 * Uses [Uuid.toLongs] for a fully cross-platform stable hash. Falls back gracefully
 * when [userId] is not a valid UUID.
 */
private fun stableColorForUserId(userId: String): Color {
    val index =
        try {
            Uuid.parse(userId).toLongs { msb, _ -> msb.hashCode() }.mod(avatarPalette.size)
        } catch (_: IllegalArgumentException) {
            userId.length.mod(avatarPalette.size)
        }
    return avatarPalette[index]
}

// ---------------------------------------------------------------------------
// Resolved UI state — computed off the recomposition hot path
// ---------------------------------------------------------------------------

/** Resolved render state for [UserAvatar], computed off the recomposition hot path. */
internal sealed interface UserAvatarUiState {
    /** Profile not yet in the local cache — show the neutral loading circle. */
    data object Loading : UserAvatarUiState

    /** A downloaded avatar image exists locally. */
    data class Image(
        val localPath: String,
        val cacheKey: String,
        val contentDescription: String,
    ) : UserAvatarUiState

    /** No local image (auto type, or image not yet downloaded) — render initials. */
    data class Initials(
        val initials: String,
        val color: Color,
    ) : UserAvatarUiState
}

/**
 * Derive up-to-two-letter initials from a display name. Returns `"?"` when blank.
 */
internal fun avatarInitials(displayName: String): String =
    displayName
        .trim()
        .split("\\s+".toRegex())
        .filter { it.isNotBlank() }
        .take(2)
        .joinToString("") { it.first().uppercase() }
        .ifBlank { "?" }

/** Pure mapping from a profile + local-avatar presence to the render state. No Compose/IO. */
internal fun userAvatarUiState(
    profile: CachedUserProfile?,
    hasLocalAvatar: Boolean,
    localPath: String,
    userId: String,
): UserAvatarUiState =
    when {
        profile == null -> UserAvatarUiState.Loading
        profile.avatarType == "image" && hasLocalAvatar ->
            UserAvatarUiState.Image(
                localPath = localPath,
                cacheKey = "$userId-avatar",
                contentDescription = profile.displayName.ifBlank { "User avatar" },
            )
        else ->
            UserAvatarUiState.Initials(
                initials = avatarInitials(profile.displayName),
                color = parseAvatarHexColor(profile.avatarColor, userId),
            )
    }

/** Twelve-color Material 3 palette for stable avatar background colors. */
private val avatarPalette =
    listOf(
        Color(0xFFE53935L),
        Color(0xFFD81B60L),
        Color(0xFF8E24AAL),
        Color(0xFF5E35B1L),
        Color(0xFF3949ABL),
        Color(0xFF1E88E5L),
        Color(0xFF039BE5L),
        Color(0xFF00ACC1L),
        Color(0xFF00897BL),
        Color(0xFF43A047L),
        Color(0xFFFB8C00L),
        Color(0xFF6D4C41L),
    )
