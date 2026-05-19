package com.calypsan.listenup.client.design.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Design system component for displaying cover images in elevated cards.
 *
 * Provides a consistent elevated card appearance for cover art throughout the app,
 * with support for optional overlays (progress indicators, edit badges, loading states).
 *
 * @param path Local file path to the cover image, or null for no image
 * @param contentDescription Accessibility description for the image
 * @param modifier Size and layout modifier for the card
 * @param bookId Book ID for server URL fallback via BookCoverImage (smart loading)
 * @param blurHash Optional BlurHash placeholder shown while cover loads
 * @param cornerRadius Rounded corner radius (default: 16.dp)
 * @param elevation Card elevation (default: 16.dp)
 * @param refreshKey Optional key to force image cache refresh
 * @param onClick Optional click handler (makes card clickable)
 * @param overlay Optional overlay content rendered on top of the image
 */
@Composable
fun ElevatedCoverCard(
    path: String?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    bookId: String? = null,
    blurHash: String? = null,
    cornerRadius: Dp = 16.dp,
    elevation: Dp = 16.dp,
    refreshKey: Any? = null,
    onClick: (() -> Unit)? = null,
    overlay: @Composable (BoxScope.() -> Unit)? = null,
) {
    val shape = RoundedCornerShape(cornerRadius)
    val cardElevation = CardDefaults.elevatedCardElevation(defaultElevation = elevation)

    if (onClick != null) {
        ElevatedCard(
            onClick = onClick,
            shape = shape,
            elevation = cardElevation,
            modifier = modifier,
        ) {
            CoverContent(
                path = path,
                contentDescription = contentDescription,
                bookId = bookId,
                blurHash = blurHash,
                refreshKey = refreshKey,
                overlay = overlay,
            )
        }
    } else {
        ElevatedCard(
            shape = shape,
            elevation = cardElevation,
            modifier = modifier,
        ) {
            CoverContent(
                path = path,
                contentDescription = contentDescription,
                bookId = bookId,
                blurHash = blurHash,
                refreshKey = refreshKey,
                overlay = overlay,
            )
        }
    }
}

@Composable
private fun CoverContent(
    path: String?,
    contentDescription: String?,
    bookId: String?,
    blurHash: String?,
    refreshKey: Any?,
    overlay: @Composable (BoxScope.() -> Unit)?,
) {
    Box(modifier = Modifier.fillMaxSize()) {
        if (bookId != null) {
            BookCoverImage(
                bookId = bookId,
                coverPath = path,
                contentDescription = contentDescription,
                blurHash = blurHash,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            ListenUpAsyncImage(
                path = path,
                contentDescription = contentDescription,
                contentScale = ContentScale.Crop,
                refreshKey = refreshKey,
                modifier = Modifier.fillMaxSize(),
            )
        }

        overlay?.invoke(this)
    }
}
