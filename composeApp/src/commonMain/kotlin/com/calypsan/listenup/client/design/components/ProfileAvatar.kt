@file:Suppress("MagicNumber")

package com.calypsan.listenup.client.design.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import coil3.compose.LocalPlatformContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import com.calypsan.listenup.client.domain.repository.ImageStorage
import com.calypsan.listenup.client.domain.repository.UserProfileRepository
import org.koin.compose.koinInject
import java.io.File
import org.jetbrains.compose.resources.stringResource
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.common_displayname_avatar

/**
 * Simple avatar component for displaying other users' profiles.
 *
 * Offline-first approach:
 * 1. Uses provided avatar data if available (avatarType, avatarValue)
 * 2. Falls back to looking up cached UserProfileEntity
 * 3. Prefers local avatar image if available
 * 4. Falls back to server URL if online
 * 5. Shows initials on colored background otherwise
 *
 * Use this component for:
 * - Activity feed items
 * - Shelves (showing owner)
 * - Book detail reader lists
 * - Any place showing another user
 *
 * For the current user's avatar with dropdown menu, use UserAvatar instead.
 *
 * @param userId The user's unique ID
 * @param displayName The user's display name (for initials)
 * @param avatarColor Fallback color in hex format (e.g., "#6B7280")
 * @param avatarType Optional avatar type ("auto" or "image") - if provided, skips DAO lookup
 * @param avatarValue Optional avatar URL path for image avatars
 * @param size Size of the avatar circle
 * @param modifier Optional modifier
 */
@Composable
fun ProfileAvatar(
    userId: String,
    displayName: String,
    avatarColor: String,
    modifier: Modifier = Modifier,
    avatarType: String? = null,
    avatarValue: String? = null,
    size: Dp = 36.dp,
) {
    val context = LocalPlatformContext.current
    val userProfileRepository: UserProfileRepository = koinInject()
    val imageStorage: ImageStorage = koinInject()

    // Use provided data if available, otherwise look up from cache
    val cachedProfile by produceState(initialValue = null as CachedProfileData?) {
        // Skip repository lookup if avatar data was provided directly
        if (avatarType != null) {
            value =
                CachedProfileData(
                    avatarType = avatarType,
                    avatarValue = avatarValue,
                    avatarColor = avatarColor,
                )
        } else {
            val profile = userProfileRepository.getById(userId)
            value =
                profile?.let {
                    CachedProfileData(
                        avatarType = it.avatarType,
                        avatarValue = it.avatarValue,
                        avatarColor = it.avatarColor,
                    )
                }
        }
    }

    val effectiveAvatarType = cachedProfile?.avatarType ?: avatarType ?: "auto"
    val effectiveAvatarColor = cachedProfile?.avatarColor ?: avatarColor

    val color =
        remember(effectiveAvatarColor) {
            parseAvatarColor(effectiveAvatarColor)
        }
    val initials =
        remember(displayName) {
            getInitials(displayName)
        }

    // Check if user has image avatar stored locally
    // NOTE: We intentionally don't use remember() here because the file may appear
    // after initial render (downloaded during sync). File existence checks are fast
    // and the Currently Listening section has only a handful of items.
    val hasLocalAvatar = imageStorage.userAvatarExists(userId)
    val localAvatarPath = if (hasLocalAvatar) imageStorage.getUserAvatarPath(userId) else null

    Box(
        modifier =
            modifier
                .size(size)
                .clip(CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        if (hasLocalAvatar && localAvatarPath != null) {
            // Load from local storage
            val file = File(localAvatarPath)
            val lastModified = file.lastModified()
            AsyncImage(
                model =
                    ImageRequest
                        .Builder(context)
                        .data(localAvatarPath)
                        .memoryCacheKey("$userId-avatar-$lastModified")
                        .diskCacheKey("$userId-avatar-$lastModified")
                        .build(),
                contentDescription = stringResource(Res.string.common_displayname_avatar, displayName),
                modifier =
                    Modifier
                        .size(size)
                        .clip(CircleShape),
                contentScale = ContentScale.Crop,
            )
        } else if (effectiveAvatarType == "image") {
            // Avatar type is image but file not yet downloaded - show placeholder
            // This handles the race condition where profile is updated before download completes
            InitialsAvatar(initials = initials, color = color, size = size)
        } else {
            // Auto-generated avatar with initials
            InitialsAvatar(initials = initials, color = color, size = size)
        }
    }
}

/**
 * Cached profile data for avatar lookup.
 */
private data class CachedProfileData(
    val avatarType: String,
    val avatarValue: String?,
    val avatarColor: String,
)

/**
 * Parse avatar color from hex string.
 */
private fun parseAvatarColor(hexColor: String): Color =
    try {
        Color(hexColor.removePrefix("#").toLong(16) or 0xFF000000)
    } catch (_: Exception) {
        Color(0xFF6B7280) // Fallback gray
    }

/**
 * Simple initials avatar.
 *
 * Font size is derived from the physical circle size (dp → px → sp) so that it
 * stays proportional to the circle at any user font-scale setting.  A raw `sp`
 * value would scale with font scale and overflow the fixed-size circle at 200%.
 */
@Composable
private fun InitialsAvatar(
    initials: String,
    color: Color,
    size: Dp,
    modifier: Modifier = Modifier,
) {
    // Derive font size from physical pixels so it stays inside the circle regardless
    // of the user's font scale setting.  ~38% of the circle diameter is a good fit
    // for two-character initials at all sizes.
    val derivedFontSize = with(LocalDensity.current) { (size.toPx() * 0.38f).toSp() }

    Box(
        modifier =
            modifier
                .size(size)
                .background(color, CircleShape),
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
