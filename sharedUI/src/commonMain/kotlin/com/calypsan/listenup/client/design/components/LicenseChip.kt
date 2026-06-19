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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.calypsan.listenup.client.design.theme.ListenUpTheme

private const val CHIP_ALPHA = 0.15f
private const val CHIP_FONT_SIZE = 12

/**
 * A small color-coded tonal pill for displaying a license identifier (e.g. "MIT", "Apache 2.0",
 * "GPL-3.0"). The background is [color] at 15% alpha; the label is [color] at full opacity with
 * bold weight so it reads clearly against any surface.
 *
 * @param label License identifier text shown inside the pill.
 * @param color The accent colour driving both the tonal background and the label.
 * @param modifier Modifier for the pill container.
 */
@Composable
fun LicenseChip(
    label: String,
    color: Color,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier =
            modifier
                .clip(RoundedCornerShape(percent = 50))
                .background(color.copy(alpha = CHIP_ALPHA))
                .padding(horizontal = 12.dp, vertical = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = color,
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
                LicenseChip(label = "MIT", color = Color(0xFF2A6FDB))
                Spacer(modifier = Modifier.width(8.dp))
                LicenseChip(label = "Apache 2.0", color = Color(0xFF1F8A5B))
                Spacer(modifier = Modifier.width(8.dp))
                LicenseChip(label = "GPL-3.0", color = Color(0xFFC2562A))
            }
        }
    }
}
