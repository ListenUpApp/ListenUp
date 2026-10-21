package com.calypsan.listenup.client.features.bookdetail.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import com.calypsan.listenup.api.error.AppError
import com.calypsan.listenup.client.design.components.ListenUpButton
import com.calypsan.listenup.client.design.haptics.LocalHaptics
import com.calypsan.listenup.client.design.theme.Spacing
import com.calypsan.listenup.client.presentation.error.localized
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.book_detail_back_to_library
import listenup.composeapp.generated.resources.book_detail_error_title
import listenup.composeapp.generated.resources.book_detail_retry
import org.jetbrains.compose.resources.stringResource

/**
 * Book Detail when the book cannot be shown (spec §3.2.3 Error, gap 24): what happened, Retry when asking again could
 * help ([AppError.isRetryable]; the page turns Ready by itself when the book arrives) and the way back to the Library.
 */
@Composable
fun BookDetailErrorState(
    error: AppError,
    onRetry: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptics = LocalHaptics.current
    Column(
        modifier = modifier.fillMaxSize().padding(horizontal = Spacing.screenMargin),
        verticalArrangement = Arrangement.spacedBy(Spacing.md, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(Res.string.book_detail_error_title),
            style = MaterialTheme.typography.titleLarge,
            textAlign = TextAlign.Center,
            modifier = Modifier.semantics { heading() },
        )
        Text(
            text = error.localized(),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        // Retry only when asking again could help: a book that no longer exists offers the way back alone.
        if (error.isRetryable) {
            ListenUpButton(
                text = stringResource(Res.string.book_detail_retry),
                onClick = onRetry,
                fillMaxWidth = false,
                leadingIcon = Icons.Default.Refresh,
            )
        }
        TextButton(
            onClick = {
                haptics.press()
                onBack()
            },
        ) { Text(stringResource(Res.string.book_detail_back_to_library)) }
    }
}
