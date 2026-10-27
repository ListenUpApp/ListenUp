package com.calypsan.listenup.client.features.library.components

import androidx.compose.runtime.Composable
import com.calypsan.listenup.client.presentation.library.BookStatusFilter
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.library_status_all
import listenup.composeapp.generated.resources.library_status_finished
import listenup.composeapp.generated.resources.library_status_in_progress
import listenup.composeapp.generated.resources.library_status_not_started
import org.jetbrains.compose.resources.stringResource

/** The reading state's word ("All", "In progress"…), for the Filter and sort trigger and its Show group. */
@Composable
internal fun BookStatusFilter.label(): String =
    stringResource(
        when (this) {
            BookStatusFilter.ALL -> Res.string.library_status_all
            BookStatusFilter.IN_PROGRESS -> Res.string.library_status_in_progress
            BookStatusFilter.NOT_STARTED -> Res.string.library_status_not_started
            BookStatusFilter.FINISHED -> Res.string.library_status_finished
        },
    )
