package com.calypsan.listenup.client.features.match

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.HourglassEmpty
import androidx.compose.material.icons.outlined.SearchOff
import androidx.compose.material.icons.outlined.Storefront
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.calypsan.listenup.api.metadata.MetadataLocale
import com.calypsan.listenup.client.design.components.ListenUpButton
import com.calypsan.listenup.client.design.theme.Spacing
import com.calypsan.listenup.client.presentation.error.localized
import com.calypsan.listenup.client.presentation.match.FindFailure
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.match_nothing_found_body
import listenup.composeapp.generated.resources.match_nothing_found_title
import listenup.composeapp.generated.resources.match_not_found_body
import listenup.composeapp.generated.resources.match_not_found_title
import listenup.composeapp.generated.resources.match_offline_body
import listenup.composeapp.generated.resources.match_offline_title
import listenup.composeapp.generated.resources.match_rate_limited_body
import listenup.composeapp.generated.resources.match_rate_limited_title
import listenup.composeapp.generated.resources.match_retry
import listenup.composeapp.generated.resources.match_retry_in
import listenup.composeapp.generated.resources.match_search_by_title
import listenup.composeapp.generated.resources.match_source_failed_body
import listenup.composeapp.generated.resources.match_source_failed_title
import listenup.composeapp.generated.resources.match_timeout_body
import listenup.composeapp.generated.resources.match_timeout_title
import listenup.composeapp.generated.resources.match_try_store
import listenup.composeapp.generated.resources.match_unexpected_title
import org.jetbrains.compose.resources.stringResource

private const val SECONDS_PER_MINUTE = 60

/**
 * Why Find has nothing to show, in the canvas's words, with the way forward each failure has. A person's Find
 * has no store and no title search, so it passes neither [onChooseStore] nor [onSearchByTitle] and those ways
 * forward aren't offered.
 */
@Composable
internal fun FindFailureContent(
    failure: FindFailure,
    onRetry: () -> Unit,
    // Nullable on purpose: null means this way forward is not offered.
    @Suppress("CanBeNonNullable")
    onChooseStore: ((MetadataLocale) -> Unit)? = null,
    // Nullable on purpose: null means this way forward is not offered.
    @Suppress("CanBeNonNullable")
    onSearchByTitle: (() -> Unit)? = null,
) {
    when (failure) {
        FindFailure.Offline -> {
            FailureMessage(
                icon = Icons.Outlined.CloudOff,
                title = stringResource(Res.string.match_offline_title),
                body = stringResource(Res.string.match_offline_body),
            ) { RetryButton(onRetry) }
        }

        is FindFailure.TimedOut -> {
            FailureMessage(
                icon = Icons.Outlined.HourglassEmpty,
                title = stringResource(Res.string.match_timeout_title, failure.source.label),
                body = stringResource(Res.string.match_timeout_body),
            ) { RetryButton(onRetry) }
        }

        is FindFailure.RateLimited -> {
            FailureMessage(
                icon = Icons.Outlined.HourglassEmpty,
                title = stringResource(Res.string.match_rate_limited_title, failure.source.label),
                body = stringResource(Res.string.match_rate_limited_body, failure.secondsRemaining),
            ) {
                val waiting = failure.secondsRemaining > 0
                ListenUpButton(
                    text =
                        if (waiting) {
                            stringResource(Res.string.match_retry_in, clock(failure.secondsRemaining))
                        } else {
                            stringResource(Res.string.match_retry)
                        },
                    onClick = onRetry,
                    enabled = !waiting,
                    fillMaxWidth = false,
                )
            }
        }

        is FindFailure.SourceFailed -> {
            FailureMessage(
                icon = Icons.Outlined.ErrorOutline,
                title = stringResource(Res.string.match_source_failed_title, failure.source.label),
                body = stringResource(Res.string.match_source_failed_body),
            ) { RetryButton(onRetry) }
        }

        is FindFailure.NotFoundInStore -> {
            FailureMessage(
                icon = Icons.Outlined.Storefront,
                title = stringResource(Res.string.match_not_found_title, failure.region.displayName),
                body = stringResource(Res.string.match_not_found_body),
            ) {
                onChooseStore?.let { choose ->
                    failure.suggestions.take(2).forEach { store ->
                        ListenUpButton(
                            text = stringResource(Res.string.match_try_store, store.displayName),
                            onClick = { choose(store) },
                            fillMaxWidth = false,
                        )
                    }
                }
                onSearchByTitle?.let { search ->
                    ListenUpButton(
                        text = stringResource(Res.string.match_search_by_title),
                        onClick = search,
                        filled = false,
                        fillMaxWidth = false,
                    )
                }
            }
        }

        FindFailure.NothingFound -> {
            FailureMessage(
                icon = Icons.Outlined.SearchOff,
                title = stringResource(Res.string.match_nothing_found_title),
                body = stringResource(Res.string.match_nothing_found_body),
            ) {
                onSearchByTitle?.let { search ->
                    ListenUpButton(
                        text = stringResource(Res.string.match_search_by_title),
                        onClick = search,
                        fillMaxWidth = false,
                    )
                }
            }
        }

        is FindFailure.Unexpected -> {
            FailureMessage(
                icon = Icons.Outlined.ErrorOutline,
                title = stringResource(Res.string.match_unexpected_title),
                body = failure.error.localized(),
            ) { RetryButton(onRetry) }
        }
    }
}

/** "0:30" — the rate-limit countdown on the disabled Retry. */
internal fun clock(seconds: Int): String {
    val minutes = seconds / SECONDS_PER_MINUTE
    val rest = seconds % SECONDS_PER_MINUTE
    return "$minutes:${rest.toString().padStart(2, '0')}"
}

@Composable
private fun RetryButton(onRetry: () -> Unit) {
    ListenUpButton(text = stringResource(Res.string.match_retry), onClick = onRetry, fillMaxWidth = false)
}

/** An icon, a heading, what happened (announced), then the ways forward. */
@Composable
internal fun FailureMessage(
    icon: ImageVector,
    title: String,
    body: String,
    actions: @Composable () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = Spacing.xl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(48.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Column(
            modifier = Modifier.semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Spacing.xs),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                textAlign = TextAlign.Center,
                modifier = Modifier.semantics { heading() },
            )
            Text(
                text = body,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
        actions()
    }
}
