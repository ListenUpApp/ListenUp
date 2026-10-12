package com.calypsan.listenup.client.presentation.error

import androidx.compose.runtime.Composable
import com.calypsan.listenup.api.error.AppError
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.allStringResources
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/** The player card's title resource for this error (`player_title_<code>`), or null when unmapped. */
internal fun AppError.playerTitleResource(): StringResource? =
    Res.allStringResources["player_title_${code.lowercase()}"]

/** The player card's cause line resource (`player_cause_<code>`), or null when unmapped. */
internal fun AppError.playerCauseResource(): StringResource? =
    Res.allStringResources["player_cause_${code.lowercase()}"]

/**
 * The player's title for this error (spec §4.6), falling back to [localized] for an error the player
 * has no wording of its own for. Rendered at the UI edge; the ViewModel carries only the typed error.
 */
@Composable
fun AppError.playerTitle(): String = playerTitleResource()?.let { stringResource(it) } ?: localized()

/** The player's one-line cause for this error, or null when there is none. */
@Composable
fun AppError.playerCause(): String? = playerCauseResource()?.let { stringResource(it) }
