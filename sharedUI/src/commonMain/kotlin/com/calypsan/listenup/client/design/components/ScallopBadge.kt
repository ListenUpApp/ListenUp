package com.calypsan.listenup.client.design.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The signature M3 Expressive scalloped "cookie" badge: a [cookieScallopShape] tile filled with
 * [containerColor], centring whatever [content] it's given. Used for count badges on section
 * headers and as the leading glyph tile for non-cover hits (series, tags).
 *
 * @param size Edge length of the square the scallop is inscribed in.
 * @param containerColor Fill behind the content; pair it with an on-container content colour.
 * @param content Centered content — typically a count [androidx.compose.material3.Text] or an icon.
 */
@Composable
fun ScallopBadge(
    modifier: Modifier = Modifier,
    size: Dp = 34.dp,
    containerColor: Color = MaterialTheme.colorScheme.primary,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(
        modifier =
            modifier
                .size(size)
                .clip(cookieScallopShape())
                .background(containerColor),
        contentAlignment = Alignment.Center,
        content = content,
    )
}
