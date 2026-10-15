package com.calypsan.listenup.client.design.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import coil3.compose.AsyncImagePainter
import com.calypsan.listenup.api.dto.image.ImageFrame

/** Test tag on the photo layer of a [ContributorPhotoCircle]; present only when the contributor has a photo. */
internal const val CONTRIBUTOR_PHOTO_TAG = "contributor-photo"

/** Test tag on the fallback layer (initials or an icon) of a [ContributorPhotoCircle]. */
internal const val CONTRIBUTOR_PHOTO_FALLBACK_TAG = "contributor-photo-fallback"

/**
 * A contributor's photo in a [size] circle, framed the way its owner chose (board `no-permission`,
 * "List row · circle 48"). The [fallback] (a row's initials or icon) draws until the photo has loaded,
 * and is all there is when [imagePath] is null, so a row never shows an empty circle.
 */
@Composable
fun ContributorPhotoCircle(
    contributorId: String,
    imagePath: String?,
    frame: ImageFrame?,
    size: Dp,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
    fallback: @Composable BoxScope.() -> Unit,
) {
    var loaded by remember(contributorId, imagePath) { mutableStateOf(false) }
    Box(
        modifier = modifier.size(size).clip(CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        if (!loaded) {
            Box(
                modifier = Modifier.matchParentSize().testTag(CONTRIBUTOR_PHOTO_FALLBACK_TAG),
                contentAlignment = Alignment.Center,
                content = fallback,
            )
        }
        if (imagePath != null) {
            Box(modifier = Modifier.matchParentSize().testTag(CONTRIBUTOR_PHOTO_TAG)) {
                ContributorCoverImage(
                    contributorId = contributorId,
                    imagePath = imagePath,
                    frame = frame,
                    contentDescription = contentDescription,
                    modifier = Modifier.matchParentSize(),
                    onState = { state -> loaded = state is AsyncImagePainter.State.Success },
                )
            }
        }
    }
}
