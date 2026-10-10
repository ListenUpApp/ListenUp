package com.calypsan.listenup.client.features.library

import androidx.compose.runtime.Composable
import androidx.compose.runtime.saveable.Saver
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.library_authors
import listenup.composeapp.generated.resources.library_books
import listenup.composeapp.generated.resources.library_narrators
import listenup.composeapp.generated.resources.library_series
import org.jetbrains.compose.resources.stringResource

/**
 * The Library's four sections (spec §3.1.1 L2). "In progress" is no longer a section: it is a status
 * filter on Books ([com.calypsan.listenup.client.presentation.library.BookStatusFilter]).
 */
enum class LibrarySection { Books, Series, Authors, Narrators }

/** Saves by name, and restores an unknown name (an older build's `InProgress`) to [LibrarySection.Books]. */
internal val LibrarySectionSaver: Saver<LibrarySection, String> =
    Saver(
        save = { it.name },
        restore = { saved -> LibrarySection.entries.firstOrNull { it.name == saved } ?: LibrarySection.Books },
    )

/** The section's catalog label. */
@Composable
internal fun LibrarySection.label(): String =
    stringResource(
        when (this) {
            LibrarySection.Books -> Res.string.library_books
            LibrarySection.Series -> Res.string.library_series
            LibrarySection.Authors -> Res.string.library_authors
            LibrarySection.Narrators -> Res.string.library_narrators
        },
    )
