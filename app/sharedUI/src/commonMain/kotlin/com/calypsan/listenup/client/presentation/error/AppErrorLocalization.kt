package com.calypsan.listenup.client.presentation.error

import androidx.compose.runtime.Composable
import com.calypsan.listenup.api.error.AppError
import com.calypsan.listenup.api.error.TransportError
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.allStringResources
import listenup.composeapp.generated.resources.error_conflict
import listenup.composeapp.generated.resources.error_forbidden
import listenup.composeapp.generated.resources.error_not_found
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource

private const val HTTP_FORBIDDEN = 403
private const val HTTP_NOT_FOUND = 404
private const val HTTP_CONFLICT = 409

/** The dynamic string-resource key an [AppError] maps to, derived from its stable [AppError.code]. */
internal fun AppError.resourceKey() = "error_${code.lowercase()}"

/**
 * Resolves an [AppError] to its localized string resource, or `null` when no resource is mapped
 * (the caller then falls back to [AppError.message]).
 *
 * [TransportError.Server4xx] is special-cased because its single code maps to several status-based
 * messages; everything else is looked up dynamically by [resourceKey].
 */
internal fun AppError.resolved(): StringResource? =
    if (this is TransportError.Server4xx) {
        when (statusCode) {
            HTTP_CONFLICT -> Res.string.error_conflict
            HTTP_FORBIDDEN -> Res.string.error_forbidden
            HTTP_NOT_FOUND -> Res.string.error_not_found
            else -> dynamicResource()
        }
    } else {
        dynamicResource()
    }

private fun AppError.dynamicResource(): StringResource? = Res.allStringResources[resourceKey()]

/**
 * Localized user-facing text for an [AppError], rendered in composition.
 *
 * Falls back to [AppError.message] for unmapped codes, so unmigrated errors still show the existing
 * English constant during the incremental localization rollout.
 */
@Composable
fun AppError.localized(): String = resolved()?.let { stringResource(it) } ?: message

/**
 * Localized user-facing text for an [AppError] usable outside composition (e.g. a snackbar
 * `LaunchedEffect`). Falls back to [AppError.message] for unmapped codes.
 */
suspend fun AppError.localizedString(): String = resolved()?.let { getString(it) } ?: message
