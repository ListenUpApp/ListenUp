package com.calypsan.listenup.client.features.library

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.outlined.AutoStories
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.RecordVoiceOver
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * Tabs for the Library screen.
 *
 * Each tab displays a different entity type from the user's collection:
 * - Books: Grid of all audiobooks
 * - Series: Series with book counts
 * - Authors: Authors with book counts
 * - Narrators: Narrators with book counts
 */
enum class LibraryTab(
    val title: String,
    val icon: ImageVector,
) {
    Books(
        title = "Books",
        icon = Icons.AutoMirrored.Outlined.MenuBook,
    ),
    Series(
        title = "Series",
        icon = Icons.Outlined.AutoStories,
    ),
    Authors(
        title = "Authors",
        icon = Icons.Outlined.Person,
    ),
    Narrators(
        title = "Narrators",
        icon = Icons.Outlined.RecordVoiceOver,
    ),
}
