package com.calypsan.listenup.client.features.bookedit.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.calypsan.listenup.client.design.components.ElevatedCoverCard
import com.calypsan.listenup.client.design.components.ListenUpLoadingIndicatorSmall
import com.calypsan.listenup.client.design.theme.DisplayFontFamily
import org.jetbrains.compose.resources.stringResource
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.book_detail_edit_book
import listenup.composeapp.generated.resources.common_back
import listenup.composeapp.generated.resources.book_edit_add_subtitle
import listenup.composeapp.generated.resources.book_edit_book_cover
import listenup.composeapp.generated.resources.book_edit_change_cover
import listenup.composeapp.generated.resources.book_edit_sort_title
import listenup.composeapp.generated.resources.book_edit_subtitle
import listenup.composeapp.generated.resources.common_title

/**
 * Color-blocked identity header for the book edit screen: a [MaterialTheme.colorScheme.primaryContainer]
 * [Surface] with large rounded bottom corners holding the back button, an "Edit Book" title, the
 * tappable cover (with a camera badge), and editable title/subtitle fields. Mirrors the canonical
 * [com.calypsan.listenup.client.design.components.ColorBlockHero] recipe.
 */
@Suppress("LongMethod")
@Composable
fun IdentityHeader(
    coverPath: String?,
    refreshKey: Any?,
    title: String,
    subtitle: String,
    sortTitle: String,
    isUploadingCover: Boolean,
    onTitleChange: (String) -> Unit,
    onSubtitleChange: (String) -> Unit,
    onSortTitleChange: (String) -> Unit,
    onCoverClick: () -> Unit,
    onBackClick: () -> Unit,
    bookId: String? = null,
    coverHash: String? = null,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        shape = RoundedCornerShape(bottomStart = 32.dp, bottomEnd = 32.dp),
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    // The primaryContainer Surface bleeds edge-to-edge behind the status bar; inset
                    // only the content so the back button clears the system clock and stays tappable.
                    .windowInsetsPadding(WindowInsets.statusBars)
                    .padding(start = 8.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
        ) {
            // Top row: back navigation + screen title
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                IconButton(onClick = onBackClick) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                        contentDescription = stringResource(Res.string.common_back),
                    )
                }
                Text(
                    text = stringResource(Res.string.book_detail_edit_book),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Cover + Title/Subtitle row
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                // Cover art (120dp) - tappable for upload
                ElevatedCoverCard(
                    path = coverPath,
                    bookId = bookId,
                    coverHash = coverHash,
                    contentDescription = stringResource(Res.string.book_edit_book_cover),
                    modifier =
                        Modifier
                            .width(120.dp)
                            .aspectRatio(1f),
                    cornerRadius = 12.dp,
                    elevation = 12.dp,
                    refreshKey = refreshKey,
                    onClick = onCoverClick,
                ) {
                    // Loading overlay during upload
                    if (isUploadingCover) {
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
                                    .padding(6.dp)
                                    .size(28.dp)
                                    .background(
                                        color = MaterialTheme.colorScheme.primary,
                                        shape = CircleShape,
                                    ),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                imageVector = Icons.Default.CameraAlt,
                                contentDescription = stringResource(Res.string.book_edit_change_cover),
                                modifier = Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.onPrimary,
                            )
                        }
                    }
                }

                // Title and Subtitle fields
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    // Title - Large editorial style
                    OutlinedTextField(
                        value = title,
                        onValueChange = onTitleChange,
                        textStyle =
                            TextStyle(
                                fontFamily = DisplayFontFamily,
                                fontWeight = FontWeight.Bold,
                                fontSize = MaterialTheme.typography.headlineSmall.fontSize,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                            ),
                        placeholder = {
                            Text(
                                stringResource(Res.string.common_title),
                                style =
                                    MaterialTheme.typography.headlineSmall.copy(
                                        fontFamily = DisplayFontFamily,
                                        fontWeight = FontWeight.Bold,
                                    ),
                                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.6f),
                            )
                        },
                        colors = heroTextFieldColors(),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth(),
                    )

                    // Subtitle - collapsed when empty, expandable via "Add subtitle" link
                    var subtitleExpanded by remember { mutableStateOf(subtitle.isNotBlank()) }
                    val subtitleFocusRequester = remember { FocusRequester() }

                    AnimatedVisibility(
                        visible = subtitleExpanded,
                        enter = expandVertically(),
                        exit = shrinkVertically(),
                    ) {
                        OutlinedTextField(
                            value = subtitle,
                            onValueChange = onSubtitleChange,
                            textStyle =
                                MaterialTheme.typography.titleMedium.copy(
                                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                                ),
                            placeholder = {
                                Text(
                                    stringResource(Res.string.book_edit_subtitle),
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.5f),
                                )
                            },
                            colors = heroTextFieldColors(),
                            shape = RoundedCornerShape(12.dp),
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .focusRequester(subtitleFocusRequester),
                        )
                    }

                    if (!subtitleExpanded) {
                        Text(
                            text = stringResource(Res.string.book_edit_add_subtitle),
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier =
                                Modifier
                                    .clickable { subtitleExpanded = true }
                                    .padding(vertical = 4.dp),
                        )
                    }

                    // Auto-focus when subtitle field is revealed
                    LaunchedEffect(subtitleExpanded) {
                        if (subtitleExpanded && subtitle.isBlank()) {
                            subtitleFocusRequester.requestFocus()
                        }
                    }

                    // Sort Title — the title's alphabetization form, kept with the identity fields
                    // so its order matches the flat iOS form (Cover, Title, Subtitle, Sort Title).
                    OutlinedTextField(
                        value = sortTitle,
                        onValueChange = onSortTitleChange,
                        label = {
                            Text(
                                stringResource(Res.string.book_edit_sort_title),
                                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f),
                            )
                        },
                        textStyle =
                            MaterialTheme.typography.bodyLarge.copy(
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                            ),
                        colors = heroTextFieldColors(),
                        shape = RoundedCornerShape(12.dp),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}

/**
 * Outlined text-field colors tuned for the `onPrimaryContainer` content of the color-blocked hero:
 * transparent containers (the field sits directly on the primaryContainer surface) with
 * `onPrimaryContainer`-derived text, cursor, and borders.
 */
@Composable
private fun heroTextFieldColors() =
    OutlinedTextFieldDefaults.colors(
        focusedTextColor = MaterialTheme.colorScheme.onPrimaryContainer,
        unfocusedTextColor = MaterialTheme.colorScheme.onPrimaryContainer,
        cursorColor = MaterialTheme.colorScheme.onPrimaryContainer,
        focusedBorderColor = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.6f),
        unfocusedBorderColor = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.3f),
        focusedContainerColor = Color.Transparent,
        unfocusedContainerColor = Color.Transparent,
    )
