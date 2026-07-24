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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.domain.model.CachedUserProfile
import com.calypsan.listenup.client.domain.repository.ImageRepository
import com.calypsan.listenup.client.domain.repository.ImageStorage
import com.calypsan.listenup.client.domain.repository.UserProfileRepository
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.koin.compose.koinInject

private val logger = KotlinLogging.logger {}

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
 *  2. Initials — the circle background is a stable per-user color derived from the user id
 *     (`stableColorForUserId`). Initials are derived from [CachedUserProfile.displayName].
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
    fallbackName: String? = null,
) {
    val baseModifier =
        modifier
            .size(size.dp)
            .clip(CircleShape)
            .let { if (onClick != null) it.clickable(onClick = onClick) else it }

    when (val state = rememberUserAvatarState(userId, fallbackName)) {
        UserAvatarUiState.Loading -> {
            LoadingPlaceholder(modifier = baseModifier)
        }

        is UserAvatarUiState.Image -> {
            LocalImageAvatar(
                localPath = state.localPath,
                cacheKey = state.cacheKey,
                contentDescription = state.contentDescription,
                modifier = baseModifier,
            )
        }

        is UserAvatarUiState.Initials -> {
            InitialsAvatar(
                initials = state.initials,
                color = state.color,
                size = size,
                modifier = baseModifier,
            )
        }
    }
}

@Composable
internal fun rememberUserAvatarState(
    userId: String,
    fallbackName: String? = null,
): UserAvatarUiState {
    val repo: UserProfileRepository = koinInject()
    val imageStorage: ImageStorage = koinInject()
    val imageRepository: ImageRepository = koinInject()
    val profile by repo.observeProfile(userId).collectAsStateWithLifecycle(initialValue = null)

    // Re-check disk presence whenever the profile's avatar version changes or a download lands, so
    // the avatar flips from initials to the real photo the moment the file appears — no revisit,
    // no manual refresh. Keying on avatarUpdatedAt (profile.updatedAt) also re-checks after a
    // synced avatar change.
    var downloadTick by remember(userId) { mutableIntStateOf(0) }
    val hasLocalAvatar =
        remember(userId, profile?.updatedAt, downloadTick) { imageStorage.userAvatarExists(userId) }

    // Persist an image avatar we don't have on disk yet — and re-run when the synced avatarUpdatedAt
    // changes — so it renders the real photo (not initials) everywhere it appears (feeds,
    // leaderboards) and survives offline, without first visiting the user's profile. A real download
    // bumps the tick, re-checking presence so the Image state renders.
    LaunchedEffect(userId, profile?.avatarType, profile?.updatedAt) {
        if (profile?.avatarType == "image") {
            when (val result = imageRepository.downloadUserAvatar(userId, forceRefresh = false)) {
                is AppResult.Success -> {
                    if (result.data) downloadTick++
                }

                is AppResult.Failure -> {
                    // Non-fatal: the avatar just keeps showing initials. Log at debug so a genuinely
                    // stuck download is diagnosable instead of silently swallowed. (AppResult already
                    // folded the failure; there is no exception/cancellation to re-raise here.)
                    logger.debug {
                        "Avatar download for $userId failed: [${result.error.code}] ${result.error.debugInfo}"
                    }
                }
            }
        }
    }

    return remember(userId, profile, fallbackName, hasLocalAvatar) {
        userAvatarUiState(
            profile = profile,
            hasLocalAvatar = hasLocalAvatar,
            localPath = imageStorage.getUserAvatarPath(userId),
            userId = userId,
            fallbackName = fallbackName,
        )
    }
}

/**
 * A resolved local avatar image, ready to render.
 *
 * @property localPath Absolute path to the avatar file in local storage.
 * @property cacheKey Coil memory/disk cache key, versioned on the profile's avatar-update time so a
 *   re-uploaded avatar re-decodes instead of serving a stale bitmap.
 * @property contentDescription Accessibility description for the image.
 */
data class UserAvatarImage(
    val localPath: String,
    val cacheKey: String,
    val contentDescription: String,
)

/**
 * Reactively resolves a user's avatar image exactly the way the canonical [UserAvatar] does —
 * observing `public_profiles`, fetching the image on first sight, and re-checking disk presence
 * when the synced avatar changes or a download completes — but hands the image back so callers can
 * render their own initials/loading chrome (the profile scallop screens).
 *
 * Returns `null` while there is no image to show (auto avatar, or an image not yet downloaded), so
 * the caller renders its own fallback. Flips to a non-null [UserAvatarImage] in real time the moment
 * the avatar downloads or a synced change lands — no revisit, no manual refresh. Whenever a user has
 * a profile image, this resolves it, so callers show the photo rather than initials.
 */
@Composable
fun rememberUserAvatarImage(userId: String): UserAvatarImage? =
    when (val state = rememberUserAvatarState(userId)) {
        is UserAvatarUiState.Image -> {
            UserAvatarImage(
                localPath = state.localPath,
                cacheKey = state.cacheKey,
                contentDescription = state.contentDescription,
            )
        }

        UserAvatarUiState.Loading, is UserAvatarUiState.Initials -> {
            null
        }
    }

// ---------------------------------------------------------------------------
// Internal rendering helpers
// ---------------------------------------------------------------------------

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
    initials: String,
    color: Color,
    size: AvatarSize,
    modifier: Modifier,
) {
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

/**
 * Pure mapping from a profile + local-avatar presence to the render state. No Compose/IO.
 *
 * [fallbackName] lets a caller render initials for a user that has no cached public profile —
 * a pending registrant never gets a server-side profile row, so without this the avatar would be
 * stuck on the indefinite loading circle. Active users (profile present) ignore it.
 */
internal fun userAvatarUiState(
    profile: CachedUserProfile?,
    hasLocalAvatar: Boolean,
    localPath: String,
    userId: String,
    fallbackName: String? = null,
): UserAvatarUiState =
    when {
        profile == null && !fallbackName.isNullOrBlank() -> {
            UserAvatarUiState.Initials(
                initials = avatarInitials(fallbackName),
                color = stableColorForUserId(userId),
            )
        }

        profile == null -> {
            UserAvatarUiState.Loading
        }

        profile.avatarType == "image" && hasLocalAvatar -> {
            UserAvatarUiState.Image(
                localPath = localPath,
                // Version the key on the profile's updatedAt: the server bumps it on avatar upload,
                // so a re-uploaded avatar gets a fresh key and Coil re-decodes the new local file
                // instead of serving the stale cached bitmap.
                cacheKey = "$userId-avatar-${profile.updatedAt}",
                contentDescription = profile.displayName.ifBlank { "User avatar" },
            )
        }

        else -> {
            UserAvatarUiState.Initials(
                initials = avatarInitials(profile.displayName),
                color = stableColorForUserId(userId),
            )
        }
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
