package com.calypsan.listenup.client.features.library.components

import androidx.compose.runtime.Composable
import com.calypsan.listenup.client.presentation.library.SortCategory
import com.calypsan.listenup.client.presentation.library.SortDirection
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.library_dir_a_to_z
import listenup.composeapp.generated.resources.library_dir_fewest
import listenup.composeapp.generated.resources.library_dir_first_added
import listenup.composeapp.generated.resources.library_dir_highest
import listenup.composeapp.generated.resources.library_dir_longest
import listenup.composeapp.generated.resources.library_dir_lowest
import listenup.composeapp.generated.resources.library_dir_most
import listenup.composeapp.generated.resources.library_dir_newest
import listenup.composeapp.generated.resources.library_dir_oldest
import listenup.composeapp.generated.resources.library_dir_recent
import listenup.composeapp.generated.resources.library_dir_series_asc
import listenup.composeapp.generated.resources.library_dir_series_desc
import listenup.composeapp.generated.resources.library_dir_shortest
import listenup.composeapp.generated.resources.library_dir_z_to_a
import listenup.composeapp.generated.resources.library_sort_added
import listenup.composeapp.generated.resources.library_sort_author
import listenup.composeapp.generated.resources.library_sort_book_count
import listenup.composeapp.generated.resources.library_sort_duration
import listenup.composeapp.generated.resources.library_sort_listener_rating
import listenup.composeapp.generated.resources.library_sort_name
import listenup.composeapp.generated.resources.library_sort_rating
import listenup.composeapp.generated.resources.library_sort_series
import listenup.composeapp.generated.resources.library_sort_title
import listenup.composeapp.generated.resources.library_sort_year
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/** The category's name from the string catalog (spec R12): "Title", "Recently added", "Length". */
@Composable
internal fun SortCategory.catalogLabel(): String = stringResource(labelResource())

/** How [direction] reads for this category: "A–Z", "Longest", "Newest first". */
@Composable
internal fun SortCategory.catalogDirectionLabel(direction: SortDirection): String =
    stringResource(directionResource(direction))

private fun SortCategory.labelResource(): StringResource =
    when (this) {
        SortCategory.TITLE -> Res.string.library_sort_title
        SortCategory.AUTHOR -> Res.string.library_sort_author
        SortCategory.SERIES -> Res.string.library_sort_series
        SortCategory.ADDED -> Res.string.library_sort_added
        SortCategory.DURATION -> Res.string.library_sort_duration
        SortCategory.YEAR -> Res.string.library_sort_year
        SortCategory.RATING -> Res.string.library_sort_rating
        SortCategory.LISTENER_RATING -> Res.string.library_sort_listener_rating
        SortCategory.NAME -> Res.string.library_sort_name
        SortCategory.BOOK_COUNT -> Res.string.library_sort_book_count
    }

private fun SortCategory.directionResource(direction: SortDirection): StringResource {
    val ascending = direction == SortDirection.ASCENDING
    return when (this) {
        SortCategory.TITLE, SortCategory.AUTHOR, SortCategory.NAME -> {
            if (ascending) Res.string.library_dir_a_to_z else Res.string.library_dir_z_to_a
        }

        SortCategory.DURATION -> {
            if (ascending) Res.string.library_dir_shortest else Res.string.library_dir_longest
        }

        SortCategory.YEAR -> {
            if (ascending) Res.string.library_dir_oldest else Res.string.library_dir_newest
        }

        SortCategory.BOOK_COUNT -> {
            if (ascending) Res.string.library_dir_fewest else Res.string.library_dir_most
        }

        SortCategory.ADDED -> {
            if (ascending) Res.string.library_dir_first_added else Res.string.library_dir_recent
        }

        SortCategory.RATING, SortCategory.LISTENER_RATING -> {
            if (ascending) Res.string.library_dir_lowest else Res.string.library_dir_highest
        }

        SortCategory.SERIES -> {
            if (ascending) Res.string.library_dir_series_asc else Res.string.library_dir_series_desc
        }
    }
}
