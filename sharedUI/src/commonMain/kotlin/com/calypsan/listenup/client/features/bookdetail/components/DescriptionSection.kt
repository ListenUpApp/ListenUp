@file:Suppress("MagicNumber")

package com.calypsan.listenup.client.features.bookdetail.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.calypsan.listenup.client.design.components.MarkdownText
import com.calypsan.listenup.client.design.theme.DisplayFontFamily
import org.jetbrains.compose.resources.stringResource
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.common_about

/**
 * Expandable description section with "Read more/less" toggle.
 *
 * Shows a preview of the description (max 120dp) with option to expand
 * for longer descriptions (>200 characters).
 */
@Composable
fun DescriptionSection(
    description: String,
    isExpanded: Boolean,
    onToggleExpanded: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        Text(
            text = stringResource(Res.string.common_about),
            style =
                MaterialTheme.typography.titleMedium.copy(
                    fontFamily = DisplayFontFamily,
                    fontWeight = FontWeight.Bold,
                ),
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(bottom = 12.dp),
        )

        Box(
            modifier =
                if (isExpanded) {
                    Modifier
                } else {
                    Modifier
                        .heightIn(max = 120.dp)
                        .clip(RoundedCornerShape(0.dp))
                },
        ) {
            MarkdownText(
                markdown = description,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (description.length > 200) {
            TextButton(
                onClick = onToggleExpanded,
                contentPadding = PaddingValues(0.dp),
            ) {
                Text(if (isExpanded) "Read less" else "Read more")
            }
        }
    }
}
