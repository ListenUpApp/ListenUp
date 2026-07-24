package com.calypsan.listenup.client.design.util

import androidx.compose.runtime.Composable

/**
 * Platform-specific clipboard copy function.
 *
 * Returns a lambda that copies [text] to the system clipboard.
 * On Android this uses ClipboardManager with ClipData,
 * on Desktop this uses the AWT Toolkit clipboard.
 */
@Composable
expect fun rememberCopyToClipboard(): (String) -> Unit
