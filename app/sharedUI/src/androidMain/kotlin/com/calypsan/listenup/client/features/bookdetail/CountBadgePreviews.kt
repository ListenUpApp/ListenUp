package com.calypsan.listenup.client.features.bookdetail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.calypsan.listenup.client.design.theme.ListenUpTheme
import com.calypsan.listenup.client.features.bookdetail.components.CountBadge

@Preview(name = "CountBadge · light", widthDp = 200, heightDp = 80)
@Composable
private fun CountBadgeLight() {
    ListenUpTheme(darkTheme = false, dynamicColor = false) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            CountBadge(count = 23)
            CountBadge(count = 1)
            CountBadge(count = 150)
        }
    }
}

@Preview(name = "CountBadge · dark", widthDp = 200, heightDp = 80)
@Composable
private fun CountBadgeDark() {
    ListenUpTheme(darkTheme = true, dynamicColor = false) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            CountBadge(count = 23)
            CountBadge(count = 1)
            CountBadge(count = 150)
        }
    }
}
