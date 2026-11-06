package com.calypsan.listenup.client.design.components

import androidx.compose.foundation.text.input.rememberTextFieldState
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

private const val SAMPLE_LABEL = "Server URL"

@Preview(name = "Empty Field")
@Composable
private fun PreviewListenUpTextFieldEmpty() {
    ListenUpTheme {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            ListenUpTextField(
                state = rememberTextFieldState(""),
                label = SAMPLE_LABEL,
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
                state = rememberTextFieldState("https://listenup.example.com"),
                label = SAMPLE_LABEL,
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
                state = rememberTextFieldState("invalid-url"),
                label = SAMPLE_LABEL,
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
                state = rememberTextFieldState(""),
                label = "Empty",
            )

            ListenUpTextField(
                state = rememberTextFieldState("https://listenup.example.com"),
                label = "Filled",
                supportingText = "Valid URL",
            )

            ListenUpTextField(
                state = rememberTextFieldState("invalid"),
                label = "Error",
                isError = true,
                supportingText = "Invalid URL format",
            )
        }
    }
}
