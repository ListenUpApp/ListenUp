@file:Suppress("MagicNumber")

package com.calypsan.listenup.client.design.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import com.calypsan.listenup.client.domain.repository.ImageStorage
import com.calypsan.listenup.client.domain.repository.ServerConfig
import org.koin.compose.koinInject
import java.io.File
import android.graphics.Color as AndroidColor

/**
 * Avatar type for display purposes.
 */
enum class AvatarDisplayType {
    /** Auto-generated avatar with initials and color */
    Auto,

    /** Custom uploaded image */
    Image,
}

/**
 * Data class representing user avatar info for display.
 */
data class UserAvatarData(
    val userId: String,
    val displayName: String,
    val avatarType: AvatarDisplayType,
    val avatarValue: String? = null,
    val avatarColor: String,
)

/**
 * Clickable circular avatar that displays user initials or image.
 *
 * Navigates to the user's profile when clicked. Supports both auto-generated
 * avatars (initials with color) and uploaded image avatars.
 *
 * @param userId The user's unique identifier
 * @param displayName The user's display name (for initials)
 * @param avatarType Type of avatar (auto or image)
 * @param avatarValue Path to avatar image (for image type)
 * @param avatarColor Hex color string for auto avatar background
 * @param onClick Callback when avatar is clicked
 * @param size Size of the avatar circle
 * @param showBorder Whether to show a border around the avatar
 * @param modifier Optional modifier
 */
@Composable
fun ClickableUserAvatar(
    userId: String,
    displayName: String,
    avatarType: AvatarDisplayType,
    avatarValue: String?,
    avatarColor: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 40.dp,
    showBorder: Boolean = false,
) {
    val context = LocalContext.current
    val serverConfig: ServerConfig = koinInject()
    val imageStorage: ImageStorage = koinInject()
    val serverUrl by produceState<String?>(null) {
        value = serverConfig.getServerUrl()?.value
    }

    val backgroundColor =
        remember(avatarColor) {
            parseAvatarColor(avatarColor)
        }
    val initials =
        remember(displayName) {
            getInitials(displayName)
        }

    // Calculate font size based on avatar size
    val fontSize =
        remember(size) {
            (size.value * 0.35f).sp
        }

    Box(
        modifier =
            modifier
                .size(size)
                .clip(CircleShape)
                .then(
                    if (showBorder) {
                        Modifier.border(2.dp, MaterialTheme.colorScheme.surface, CircleShape)
                    } else {
                        Modifier
                    },
                ).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        when (avatarType) {
            AvatarDisplayType.Auto -> {
                // Auto-generated avatar with initials
                Box(
                    modifier =
                        Modifier
                            .matchParentSize()
                            .background(backgroundColor, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = initials,
                        color = Color.White,
                        fontSize = fontSize,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center,
                    )
                }
            }

            AvatarDisplayType.Image -> {
                // Offline-first: prefer local cached avatar
                val localPath =
                    if (imageStorage.userAvatarExists(userId)) {
                        imageStorage.getUserAvatarPath(userId)
                    } else {
                        null
                    }

                when {
                    localPath != null -> {
                        // Use local file with modification time as cache buster
                        val file = File(localPath)
                        val lastModified = file.lastModified()
                        AsyncImage(
                            model =
                                ImageRequest
                                    .Builder(context)
                                    .data(localPath)
                                    .memoryCacheKey("$userId-avatar-$lastModified")
                                    .diskCacheKey("$userId-avatar-$lastModified")
                                    .build(),
                            contentDescription = "$displayName avatar",
                            modifier =
                                Modifier
                                    .matchParentSize()
                                    .clip(CircleShape),
                            contentScale = ContentScale.Crop,
                        )
                    }

                    serverUrl != null && avatarValue != null -> {
                        // Fallback: fetch from server with disabled caching
                        val avatarUrl = "$serverUrl$avatarValue"
                        AsyncImage(
                            model =
                                ImageRequest
                                    .Builder(context)
                                    .data(avatarUrl)
                                    .memoryCachePolicy(CachePolicy.DISABLED)
                                    .diskCachePolicy(CachePolicy.DISABLED)
                                    .build(),
                            contentDescription = "$displayName avatar",
                            modifier =
                                Modifier
                                    .matchParentSize()
                                    .clip(CircleShape),
                            contentScale = ContentScale.Crop,
                        )
                    }

                    else -> {
                        // No local file and no server URL - fallback to initials
                        Box(
                            modifier =
                                Modifier
                                    .matchParentSize()
                                    .background(backgroundColor, CircleShape),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = initials,
                                color = Color.White,
                                fontSize = fontSize,
                                fontWeight = FontWeight.Bold,
                                textAlign = TextAlign.Center,
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Convenience overload that accepts UserAvatarData.
 */
@Composable
fun ClickableUserAvatar(
    avatarData: UserAvatarData,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 40.dp,
    showBorder: Boolean = false,
) {
    ClickableUserAvatar(
        userId = avatarData.userId,
        displayName = avatarData.displayName,
        avatarType = avatarData.avatarType,
        avatarValue = avatarData.avatarValue,
        avatarColor = avatarData.avatarColor,
        onClick = onClick,
        modifier = modifier,
        size = size,
        showBorder = showBorder,
    )
}

/**
 * Parse avatar color from hex string.
 *
 * @param hexColor Color in "#RRGGBB" format
 * @return Compose Color, or fallback gray if parsing fails
 */
private fun parseAvatarColor(hexColor: String): Color =
    try {
        Color(AndroidColor.parseColor(hexColor))
    } catch (_: Exception) {
        Color(0xFF6B7280) // Fallback gray
    }
