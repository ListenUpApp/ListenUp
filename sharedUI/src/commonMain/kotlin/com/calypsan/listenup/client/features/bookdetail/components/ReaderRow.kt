@file:Suppress("MagicNumber", "SwallowedException", "CognitiveComplexMethod")

package com.calypsan.listenup.client.features.bookdetail.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.calypsan.listenup.client.design.components.getInitials
import com.calypsan.listenup.client.design.util.parseHexColor
import com.calypsan.listenup.client.domain.model.ReaderInfo
import com.calypsan.listenup.client.domain.repository.ServerConfig
import com.calypsan.listenup.client.util.toRelativeOrMonthYear
import org.koin.compose.koinInject

/**
 * Row displaying another reader's status with a book.
 *
 * Shows:
 * - Avatar with generated color and initials (or image if user has uploaded one)
 * - Display name
 * - Reading status (currently reading or finished)
 * - Progress percentage if currently reading
 * - Completion count if multiple completions
 *
 * @param reader The reader summary data
 * @param onUserClick Optional callback when the reader row is clicked (navigates to profile)
 * @param modifier Optional modifier
 */
@Composable
fun ReaderRow(
    reader: ReaderInfo,
    modifier: Modifier = Modifier,
    onUserClick: ((String) -> Unit)? = null,
) {
    val serverConfig: ServerConfig = koinInject()
    val serverUrl by produceState<String?>(null) {
        value = serverConfig.getServerUrl()?.value
    }

    val hasImageAvatar = reader.avatarType == "image" && !reader.avatarValue.isNullOrEmpty()

    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .then(
                    if (onUserClick != null) {
                        Modifier.clickable { onUserClick(reader.userId) }
                    } else {
                        Modifier
                    },
                ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Start,
    ) {
        // Avatar - either image or generated with initials
        if (hasImageAvatar && serverUrl != null) {
            val avatarUrl = "$serverUrl${reader.avatarValue}"
            AsyncImage(
                model = avatarUrl,
                contentDescription = "${reader.displayName} avatar",
                modifier =
                    Modifier
                        .size(40.dp)
                        .clip(CircleShape),
                contentScale = ContentScale.Crop,
            )
        } else {
            // Auto-generated avatar with initials
            val avatarColorParsed = parseHexColor(reader.avatarColor)

            Surface(
                shape = CircleShape,
                color = avatarColorParsed,
                modifier = Modifier.size(40.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        text = getInitials(reader.displayName),
                        style = MaterialTheme.typography.titleSmall,
                        color = Color.White,
                    )
                }
            }
        }

        Spacer(modifier = Modifier.width(12.dp))

        // Reader info
        Column(modifier = Modifier.weight(1f)) {
            // Display name - show "You" for current user
            Text(
                text = if (reader.isCurrentUser) "You" else reader.displayName,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
            )

            // Status line - show when they last read
            val statusText =
                buildString {
                    if (reader.isCurrentlyReading) {
                        append("Currently reading")
                    } else {
                        append("Last read ${reader.lastActivityAt.toRelativeOrMonthYear()}")
                    }
                }

            Text(
                text = statusText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            // Additional info line: progress or completions
            val additionalText =
                buildString {
                    if (reader.isCurrentlyReading && reader.currentProgress > 0) {
                        val progressPercent = (reader.currentProgress * 100).toInt()
                        append("$progressPercent% complete")
                    } else if (reader.completionCount > 1) {
                        append("Read ${reader.completionCount} times")
                    }
                }

            if (additionalText.isNotEmpty()) {
                Text(
                    text = additionalText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
