@file:Suppress("StringLiteralDuplication")

package com.calypsan.listenup.client.design.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.calypsan.listenup.client.design.theme.ListenUpTheme

@Preview(name = "Normal State")
@Composable
private fun PreviewListenUpButtonNormal() {
    ListenUpTheme {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            ListenUpButton(
                text = "Connect to Server",
                onClick = {},
            )
        }
    }
}

@Preview(name = "Disabled State")
@Composable
private fun PreviewListenUpButtonDisabled() {
    ListenUpTheme {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            ListenUpButton(
                text = "Connect to Server",
                onClick = {},
                enabled = false,
            )
        }
    }
}

@Preview(name = "Loading State")
@Composable
private fun PreviewListenUpButtonLoading() {
    ListenUpTheme {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            ListenUpButton(
                text = "Connect to Server",
                onClick = {},
                isLoading = true,
            )
        }
    }
}

@Preview(name = "All States")
@Composable
private fun PreviewListenUpButtonStates() {
    ListenUpTheme {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            ListenUpButton(
                text = "Normal",
                onClick = {},
            )

            ListenUpButton(
                text = "Disabled",
                onClick = {},
                enabled = false,
            )

            ListenUpButton(
                text = "Loading",
                onClick = {},
                isLoading = true,
            )
        }
    }
}
