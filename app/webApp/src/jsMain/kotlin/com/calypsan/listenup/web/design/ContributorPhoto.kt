package com.calypsan.listenup.web.design

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.calypsan.listenup.api.dto.image.ImageFrame
import org.jetbrains.compose.web.dom.Text

/**
 * A contributor's photo, framed, over their monogram. The monogram is the finished avatar for the
 * majority with no photo, and the fallback when the photo 404s: a routine case, never reported.
 *
 * Web drew initials only on Contributors and Contributor Detail while Android and iOS showed photos
 * (spec Q5, a parity stop). This closes it, and the search rows and book credits use it too.
 *
 * It draws only the content: the caller's own circle (`.contrib-avatar`, `.cd-avatar`, …) keeps its
 * size, its tint (which shows under the photo while it loads) and its ring. `alt` is empty because
 * every caller is `aria-hidden` and named by the text beside it.
 */
@Composable
fun ContributorPhoto(
    contributorId: String,
    name: String,
    imagePath: String?,
    frame: ImageFrame?,
) {
    var failed by remember(contributorId, imagePath) { mutableStateOf(false) }
    if (imagePath != null && !failed) {
        FramedImg(
            src = contributorPhotoUrl(contributorId, imagePath),
            alt = "",
            frame = frame,
            classes = listOf("contrib-photo"),
            onError = { failed = true },
        )
    } else {
        Text(initialsFor(name))
    }
}
