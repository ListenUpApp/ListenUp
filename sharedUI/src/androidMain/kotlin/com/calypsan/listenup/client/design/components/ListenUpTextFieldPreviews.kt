@file:Suppress("StringLiteralDuplication")

package com.calypsan.listenup.client.design.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.calypsan.listenup.client.design.theme.ListenUpTheme

@Preview(name = "Empty Field")
@Composable
private fun PreviewListenUpTextFieldEmpty() {
    ListenUpTheme {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            ListenUpTextField(
                value = "",
                onValueChange = {},
                label = "Server URL",
                placeholder = "https://example.com",
            )
        }
    }
}

@Preview(name = "With Text")
@Composable
private fun PreviewListenUpTextFieldWithText() {
    ListenUpTheme {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            ListenUpTextField(
                value = "https://listenup.example.com",
                onValueChange = {},
                label = "Server URL",
                supportingText = "Enter your ListenUp server address",
            )
        }
    }
}

@Preview(name = "Error State")
@Composable
private fun PreviewListenUpTextFieldError() {
    ListenUpTheme {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            ListenUpTextField(
                value = "invalid-url",
                onValueChange = {},
                label = "Server URL",
                isError = true,
                supportingText = "Invalid URL format. Use https://example.com",
                keyboardOptions =
                    KeyboardOptions(
                        keyboardType = KeyboardType.Uri,
                        imeAction = ImeAction.Done,
                    ),
            )
        }
    }
}

@Preview(name = "Multiple States")
@Composable
private fun PreviewListenUpTextFieldStates() {
    ListenUpTheme {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            ListenUpTextField(
                value = "",
                onValueChange = {},
                label = "Empty",
            )

            ListenUpTextField(
                value = "https://listenup.example.com",
                onValueChange = {},
                label = "Filled",
                supportingText = "Valid URL",
            )

            ListenUpTextField(
                value = "invalid",
                onValueChange = {},
                label = "Error",
                isError = true,
                supportingText = "Invalid URL format",
            )
        }
    }
}
