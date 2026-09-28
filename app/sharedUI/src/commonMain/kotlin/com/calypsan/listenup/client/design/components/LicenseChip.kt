package com.calypsan.listenup.client.design.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.calypsan.listenup.client.design.theme.CategoryColor
import com.calypsan.listenup.client.design.theme.CategoryPalette
import com.calypsan.listenup.client.design.theme.ListenUpTheme

private const val CHIP_FONT_SIZE = 12

/**
 * A small color-coded tonal pill for displaying a license identifier (e.g. "MIT", "Apache 2.0",
 * "GPL-3.0"). The fill and the bold label come from [color]'s tone for the current theme, a pair
 * composed to clear WCAG AA for text in both light and dark.
 *
 * @param label License identifier text shown inside the pill.
 * @param color The licence family's category colour.
 * @param modifier Modifier for the pill container.
 */
@Composable
fun LicenseChip(
    label: String,
    color: CategoryColor,
    modifier: Modifier = Modifier,
) {
    val tone = color.current
    Box(
        modifier =
            modifier
                .clip(RoundedCornerShape(percent = 50))
                .background(tone.container)
                .padding(horizontal = 12.dp, vertical = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = tone.content,
            fontWeight = FontWeight.Bold,
            fontSize = CHIP_FONT_SIZE.sp,
        )
    }
}

@Preview
@Composable
private fun LicenseChipPreview() {
    ListenUpTheme {
        Surface(color = MaterialTheme.colorScheme.surface) {
            Row(
                modifier = Modifier.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                LicenseChip(label = "MIT", color = CategoryPalette.Green)
                Spacer(modifier = Modifier.width(8.dp))
                LicenseChip(label = "Apache 2.0", color = CategoryPalette.Blue)
                Spacer(modifier = Modifier.width(8.dp))
                LicenseChip(label = "GPL-3.0", color = CategoryPalette.Red)
            }
        }
    }
}
