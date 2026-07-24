package com.calypsan.listenup.client.features.contributoredit.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.calypsan.listenup.client.design.components.ContributorCoverImage
import com.calypsan.listenup.client.design.components.ListenUpLoadingIndicatorSmall
import com.calypsan.listenup.client.design.components.ListenUpTextField
import com.calypsan.listenup.client.design.components.ListenUpTextFieldVariant

import com.calypsan.listenup.client.design.theme.DisplayFontFamily
import org.jetbrains.compose.resources.stringResource
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.common_back
import listenup.composeapp.generated.resources.common_name
import listenup.composeapp.generated.resources.contributor_change_photo
import listenup.composeapp.generated.resources.contributor_contributor_photo

/**
 * Identity header with large avatar and name field side by side.
 */
@Suppress("LongMethod")
@Composable
fun ContributorIdentityHeader(
    contributorId: String,
    imagePath: String?,
    stagingImagePath: String?,
    name: String,
    colorScheme: ContributorColorScheme,
    isUploadingImage: Boolean,
    onNameChange: (String) -> Unit,
    onAvatarClick: () -> Unit,
    onBackClick: () -> Unit,
) {
    val surfaceColor = MaterialTheme.colorScheme.surface

    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.statusBars)
                .padding(16.dp),
    ) {
        // Floating back button
        IconButton(
            onClick = onBackClick,
            modifier =
                Modifier
                    .size(48.dp)
                    .background(
                        color = surfaceColor.copy(alpha = 0.5f),
                        shape = CircleShape,
                    ),
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = stringResource(Res.string.common_back),
                tint = MaterialTheme.colorScheme.onSurface,
            )
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Avatar + Name row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Large editable avatar (120dp) - tappable for upload
            ElevatedCard(
                onClick = onAvatarClick,
                shape = CircleShape,
                elevation = CardDefaults.elevatedCardElevation(defaultElevation = 12.dp),
                colors =
                    CardDefaults.elevatedCardColors(
                        containerColor = colorScheme.primary,
                    ),
                modifier = Modifier.size(120.dp),
            ) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    var imageLoaded by remember(contributorId) { mutableStateOf(false) }

                    if (!imageLoaded) {
                        Text(
                            text =
                                name
                                    .trim()
                                    .split("\\s+".toRegex())
                                    .let { parts ->
                                        when {
                                            parts.size >= 2 -> "${parts[0].first()}${parts[1].first()}"
                                            name.length >= 2 -> name.take(2)
                                            else -> name.take(1)
                                        }
                                    }.uppercase(),
                            style =
                                MaterialTheme.typography.displaySmall.copy(
                                    fontFamily = DisplayFontFamily,
                                    fontWeight = FontWeight.Bold,
                                ),
                            color = colorScheme.onPrimary,
                        )
                    }

                    ContributorCoverImage(
                        contributorId = contributorId,
                        imagePath = imagePath,
                        stagingImagePath = stagingImagePath,
                        contentDescription = stringResource(Res.string.contributor_contributor_photo),
                        contentScale = ContentScale.Crop,
                        modifier =
                            Modifier
                                .fillMaxSize()
                                .clip(CircleShape),
                        onState = { state ->
                            if (state is coil3.compose.AsyncImagePainter.State.Success) {
                                imageLoaded = true
                            }
                        },
                    )

                    // Loading overlay during upload
                    if (isUploadingImage) {
                        Box(
                            modifier =
                                Modifier
                                    .fillMaxSize()
                                    .background(Color.Black.copy(alpha = 0.5f)),
                            contentAlignment = Alignment.Center,
                        ) {
                            ListenUpLoadingIndicatorSmall()
                        }
                    } else {
                        // Edit indicator
                        Box(
                            modifier =
                                Modifier
                                    .align(Alignment.BottomEnd)
                                    .padding(4.dp)
                                    .size(32.dp)
                                    .background(
                                        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.9f),
                                        shape = CircleShape,
                                    ),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                imageVector = Icons.Default.CameraAlt,
                                contentDescription = stringResource(Res.string.contributor_change_photo),
                                modifier = Modifier.size(18.dp),
                                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            )
                        }
                    }
                }
            }

            // Name field — large editorial hero variant of the canonical text field.
            ListenUpTextField(
                value = name,
                onValueChange = onNameChange,
                placeholder = stringResource(Res.string.common_name),
                variant = ListenUpTextFieldVariant.Hero,
                heroContainerColor = surfaceColor,
                modifier = Modifier.weight(1f),
            )
        }
    }
}
