package com.calypsan.listenup.client.design.util

import androidx.compose.runtime.Composable
import com.calypsan.listenup.api.sync.ExternalRatingSource
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.rating_source_audible
import listenup.composeapp.generated.resources.rating_source_goodreads
import listenup.composeapp.generated.resources.rating_source_hardcover
import org.jetbrains.compose.resources.stringResource

/**
 * [source]'s display name, shared by the Book Detail rating breakdown and the admin rating-sources
 * list. [ExternalRatingSource.UNKNOWN] never reaches either surface — both filter it out before
 * display — but the `when` still needs a branch, so it falls back to the raw enum name.
 */
@Composable
fun ratingSourceLabel(source: ExternalRatingSource): String =
    when (source) {
        ExternalRatingSource.AUDIBLE -> stringResource(Res.string.rating_source_audible)
        ExternalRatingSource.HARDCOVER -> stringResource(Res.string.rating_source_hardcover)
        ExternalRatingSource.GOODREADS -> stringResource(Res.string.rating_source_goodreads)
        ExternalRatingSource.UNKNOWN -> source.name
    }
