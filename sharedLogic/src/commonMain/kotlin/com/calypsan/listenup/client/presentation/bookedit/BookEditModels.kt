
package com.calypsan.listenup.client.presentation.bookedit

import com.calypsan.listenup.client.domain.model.ContributorSearchResult
import com.calypsan.listenup.client.domain.model.SeriesSearchResult
import com.calypsan.listenup.client.domain.model.ContributorRole as DomainContributorRole
import com.calypsan.listenup.client.domain.model.EditableCollection as DomainEditableCollection
import com.calypsan.listenup.client.domain.model.EditableContributor as DomainEditableContributor
import com.calypsan.listenup.client.domain.model.EditableGenre as DomainEditableGenre
import com.calypsan.listenup.client.domain.model.EditableMood as DomainEditableMood
import com.calypsan.listenup.client.domain.model.EditableSeries as DomainEditableSeries
import com.calypsan.listenup.client.domain.model.EditableTag as DomainEditableTag

// Type aliases to domain types - presentation layer uses domain models directly
typealias EditableContributor = DomainEditableContributor
typealias EditableSeries = DomainEditableSeries
typealias EditableGenre = DomainEditableGenre
typealias EditableTag = DomainEditableTag
typealias EditableMood = DomainEditableMood
typealias EditableCollection = DomainEditableCollection
typealias ContributorRole = DomainContributorRole

/**
 * Extension property for ContributorRole display name.
 * Presentation-layer concern for UI display.
 */
val ContributorRole.displayName: String
    get() =
        when (this) {
            ContributorRole.AUTHOR -> "Author"
            ContributorRole.NARRATOR -> "Narrator"
            ContributorRole.EDITOR -> "Editor"
            ContributorRole.TRANSLATOR -> "Translator"
            ContributorRole.FOREWORD -> "Foreword"
            ContributorRole.INTRODUCTION -> "Introduction"
            ContributorRole.AFTERWORD -> "Afterword"
            ContributorRole.PRODUCER -> "Producer"
            ContributorRole.ADAPTER -> "Adapter"
            ContributorRole.ILLUSTRATOR -> "Illustrator"
        }

/**
 * Extension property for EditableGenre parent path display.
 * Returns the parent path for display context.
 * "/fiction/fantasy/epic-fantasy" -> "Fiction > Fantasy"
 */
val EditableGenre.parentPath: String?
    get() {
        val segments = path.trim('/').split('/')
        if (segments.size <= 1) return null
        return segments
            .dropLast(1)
            .joinToString(" > ") { it.replaceFirstChar { c -> c.uppercase() } }
    }

/**
 * Extension function for EditableTag display name.
 * Human-readable display name derived from slug.
 * "found-family" -> "Found Family"
 */
fun EditableTag.displayName(): String =
    slug
        .split("-")
        .joinToString(" ") { word ->
            word.replaceFirstChar { it.titlecase() }
        }

/**
 * Extension function for EditableMood display name.
 * Human-readable display name derived from slug.
 * "feel-good" -> "Feel Good"
 */
fun EditableMood.displayName(): String =
    slug
        .split("-")
        .joinToString(" ") { word ->
            word.replaceFirstChar { it.titlecase() }
        }

/**
 * UI state for book editing screen.
 */
data class BookEditUiState(
    // Loading states
    val isLoading: Boolean = true,
    val isSaving: Boolean = false,
    val isUploadingCover: Boolean = false,
    val error: String? = null,
    // Book identity (for immersive header)
    val coverPath: String? = null,
    val coverHash: String? = null,
    // Book metadata fields
    val bookId: String = "",
    val title: String = "",
    val sortTitle: String = "",
    val subtitle: String = "",
    val description: String = "",
    val publishYear: String = "",
    val publisher: String = "",
    val language: String? = null, // ISO 639-1 code
    // Additional metadata (less commonly edited)
    val isbn: String = "",
    val asin: String = "",
    val abridged: Boolean = false,
    // Library metadata
    val addedAt: Long? = null, // Epoch milliseconds when book was added to library
    // Contributors
    val contributors: List<EditableContributor> = emptyList(),
    // Series (multi-series support)
    val series: List<EditableSeries> = emptyList(),
    val seriesSearchQuery: String = "",
    val seriesSearchResults: List<SeriesSearchResult> = emptyList(),
    val seriesSearchLoading: Boolean = false,
    val seriesOfflineResult: Boolean = false,
    // Per-role search state (replaces single search)
    val roleSearchQueries: Map<ContributorRole, String> = emptyMap(),
    val roleSearchResults: Map<ContributorRole, List<ContributorSearchResult>> = emptyMap(),
    val roleSearchLoading: Map<ContributorRole, Boolean> = emptyMap(),
    val roleOfflineResults: Map<ContributorRole, Boolean> = emptyMap(),
    // Visible role sections (prepopulated from existing contributors + user-added)
    val visibleRoles: Set<ContributorRole> = emptySet(),
    // Genres (system-controlled, select from existing)
    val genres: List<EditableGenre> = emptyList(),
    val allGenres: List<EditableGenre> = emptyList(), // Cached list from server
    val genreSearchQuery: String = "",
    val genreSearchResults: List<EditableGenre> = emptyList(), // Filtered locally
    // Tags (global community descriptors)
    val tags: List<EditableTag> = emptyList(),
    val allTags: List<EditableTag> = emptyList(), // All available tags
    val tagSearchQuery: String = "",
    val tagSearchResults: List<EditableTag> = emptyList(),
    val tagSearchLoading: Boolean = false,
    val tagCreating: Boolean = false, // Creating a new tag
    // Moods (global affective descriptors)
    val moods: List<EditableMood> = emptyList(),
    val allMoods: List<EditableMood> = emptyList(), // All available moods
    val moodSearchQuery: String = "",
    val moodSearchResults: List<EditableMood> = emptyList(),
    val moodSearchLoading: Boolean = false,
    val moodCreating: Boolean = false, // Creating a new mood
    // Collections (admin-only, select from existing)
    val collections: List<EditableCollection> = emptyList(),
    val allCollections: List<EditableCollection> = emptyList(), // All accessible collections
    val collectionSearchQuery: String = "",
    val collectionSearchResults: List<EditableCollection> = emptyList(), // Filtered locally
    // Whether the current user is an admin (gates the Collections field)
    val isAdmin: Boolean = false,
    // Track if changes have been made
    val hasChanges: Boolean = false,
    // Pending cover upload (stored until Save Changes)
    val pendingCoverData: ByteArray? = null,
    val pendingCoverFilename: String? = null,
    // Staging cover path for preview (separate from main cover)
    val stagingCoverPath: String? = null,
) {
    /**
     * Returns the cover path to display - staging if available, otherwise original.
     */
    val displayCoverPath: String?
        get() = stagingCoverPath ?: coverPath

    /**
     * Get contributors for a specific role.
     */
    fun contributorsForRole(role: ContributorRole): List<EditableContributor> = contributors.filter { role in it.roles }

    /**
     * Authors from contributor list.
     */
    val authors: List<EditableContributor>
        get() = contributorsForRole(ContributorRole.AUTHOR)

    /**
     * Narrators from contributor list.
     */
    val narrators: List<EditableContributor>
        get() = contributorsForRole(ContributorRole.NARRATOR)

    /**
     * Roles that are not yet visible (available to add).
     */
    val availableRolesToAdd: List<ContributorRole>
        get() = ContributorRole.entries.filter { it !in visibleRoles }

    // --- Bridge-safe flat accessors for iOS (Swift Export) ---
    // The [roleSearchQueries]/[roleSearchLoading]/[roleSearchResults] maps above are keyed by the
    // [ContributorRole] enum. Compose reads them directly (Kotlin subscripts an enum-keyed map
    // natively), but Swift MUST NOT: Swift Export bridges a `Map<ContributorRole, …>` as
    // `[ContributorRole: …]`, and reading it force-casts the AnyHashable-boxed keys back to the
    // bridged enum, which traps ("Could not cast Swift.AnyHashable to …ContributorRole") and crashes
    // any book edit. iOS reads these plain-typed projections instead. Only author/narrator are
    // searchable in the add-picker, so only those are exposed.
    val authorSearchQuery: String get() = roleSearchQueries[ContributorRole.AUTHOR].orEmpty()
    val narratorSearchQuery: String get() = roleSearchQueries[ContributorRole.NARRATOR].orEmpty()
    val authorSearchLoading: Boolean get() = roleSearchLoading[ContributorRole.AUTHOR] == true
    val narratorSearchLoading: Boolean get() = roleSearchLoading[ContributorRole.NARRATOR] == true
    val authorSearchResults: List<ContributorSearchResult>
        get() = roleSearchResults[ContributorRole.AUTHOR].orEmpty()
    val narratorSearchResults: List<ContributorSearchResult>
        get() = roleSearchResults[ContributorRole.NARRATOR].orEmpty()
}

/**
 * Events from the book edit UI.
 */
sealed interface BookEditUiEvent {
    // Metadata changes

    /** User edited the title field. */
    data class TitleChanged(
        val title: String,
    ) : BookEditUiEvent

    /** User edited the sort-title field (used for alphabetical sorting that ignores leading articles). */
    data class SortTitleChanged(
        val sortTitle: String,
    ) : BookEditUiEvent

    /** User edited the subtitle field. */
    data class SubtitleChanged(
        val subtitle: String,
    ) : BookEditUiEvent

    /** User edited the description field. */
    data class DescriptionChanged(
        val description: String,
    ) : BookEditUiEvent

    /** User edited the publish-year field. */
    data class PublishYearChanged(
        val year: String,
    ) : BookEditUiEvent

    /** User edited the publisher field. */
    data class PublisherChanged(
        val publisher: String,
    ) : BookEditUiEvent

    /** User picked a language (ISO 639-1 code) or cleared the selection (`null`). */
    data class LanguageChanged(
        val code: String?,
    ) : BookEditUiEvent

    // Additional metadata

    /** User edited the ISBN field. */
    data class IsbnChanged(
        val isbn: String,
    ) : BookEditUiEvent

    /** User edited the ASIN field. */
    data class AsinChanged(
        val asin: String,
    ) : BookEditUiEvent

    /** User toggled the abridged flag. */
    data class AbridgedChanged(
        val abridged: Boolean,
    ) : BookEditUiEvent

    // Library metadata

    /** User changed the "added to library" timestamp; `null` clears it back to "use detected value". */
    data class AddedAtChanged(
        val epochMillis: Long?,
    ) : BookEditUiEvent

    // Series management

    /** User typed in the series search box. */
    data class SeriesSearchQueryChanged(
        val query: String,
    ) : BookEditUiEvent

    /** User picked an existing series from search results. */
    data class SeriesSelected(
        val result: SeriesSearchResult,
    ) : BookEditUiEvent

    /** User submitted a new series name (no existing match). */
    data class SeriesEntered(
        val name: String,
    ) : BookEditUiEvent

    /** User updated this book's sequence number within a series. */
    data class SeriesSequenceChanged(
        val series: EditableSeries,
        val sequence: String,
    ) : BookEditUiEvent

    /** User detached the book from a series. */
    data class RemoveSeries(
        val series: EditableSeries,
    ) : BookEditUiEvent

    data object ClearSeriesSearch : BookEditUiEvent

    // Per-role contributor management

    /** User typed in the search box for a specific contributor role. */
    data class RoleSearchQueryChanged(
        val role: ContributorRole,
        val query: String,
    ) : BookEditUiEvent

    /** User picked an existing contributor from the role's search results. */
    data class RoleContributorSelected(
        val role: ContributorRole,
        val result: ContributorSearchResult,
    ) : BookEditUiEvent

    /** User submitted a new contributor name for a role (no existing match). */
    data class RoleContributorEntered(
        val role: ContributorRole,
        val name: String,
    ) : BookEditUiEvent

    /** User cleared the search query for a specific role. */
    data class ClearRoleSearch(
        val role: ContributorRole,
    ) : BookEditUiEvent

    /** User opted to show a previously-hidden role section so they can add contributors to it. */
    data class AddRoleSection(
        val role: ContributorRole,
    ) : BookEditUiEvent

    /** User detached one contributor from one role (other roles for the same contributor stay attached). */
    data class RemoveContributor(
        val contributor: EditableContributor,
        val role: ContributorRole,
    ) : BookEditUiEvent

    /** User hid an entire role section, removing all of that role's contributors at once. */
    data class RemoveRoleSection(
        val role: ContributorRole,
    ) : BookEditUiEvent

    // Genre management (select from existing only)

    /** User typed in the genre search box. */
    data class GenreSearchQueryChanged(
        val query: String,
    ) : BookEditUiEvent

    /** User picked an existing genre to attach to the book. */
    data class GenreSelected(
        val genre: EditableGenre,
    ) : BookEditUiEvent

    /** User detached a genre from the book. */
    data class RemoveGenre(
        val genre: EditableGenre,
    ) : BookEditUiEvent

    // Tag management (select existing or create new)

    /** User typed in the tag search box. */
    data class TagSearchQueryChanged(
        val query: String,
    ) : BookEditUiEvent

    /** User picked an existing tag to attach. */
    data class TagSelected(
        val tag: EditableTag,
    ) : BookEditUiEvent

    /** User submitted a new tag name; ViewModel creates the tag inline before attaching. */
    data class TagEntered(
        val name: String,
    ) : BookEditUiEvent

    /** User detached a tag from the book. */
    data class RemoveTag(
        val tag: EditableTag,
    ) : BookEditUiEvent

    // Mood management (select existing or create new)

    /** User typed in the mood search box. */
    data class MoodSearchQueryChanged(
        val query: String,
    ) : BookEditUiEvent

    /** User picked an existing mood to attach. */
    data class MoodSelected(
        val mood: EditableMood,
    ) : BookEditUiEvent

    /** User submitted a new mood name; ViewModel creates the mood inline before attaching. */
    data class MoodEntered(
        val name: String,
    ) : BookEditUiEvent

    /** User detached a mood from the book. */
    data class RemoveMood(
        val mood: EditableMood,
    ) : BookEditUiEvent

    // Collection management (admin-only, select from existing)

    /** User typed in the collection search box. */
    data class CollectionSearchQueryChanged(
        val query: String,
    ) : BookEditUiEvent

    /** User picked an existing collection to attach to the book. */
    data class CollectionSelected(
        val collection: EditableCollection,
    ) : BookEditUiEvent

    /** User detached a collection from the book. */
    data class RemoveCollection(
        val collection: EditableCollection,
    ) : BookEditUiEvent

    // Cover upload

    /** User chose an image to use as the book's cover; bytes are held pending Save. */
    data class UploadCover(
        val imageData: ByteArray,
        val filename: String,
    ) : BookEditUiEvent

    // Actions
    data object Save : BookEditUiEvent

    data object Cancel : BookEditUiEvent

    data object DismissError : BookEditUiEvent
}

/**
 * Navigation actions from book edit screen.
 */
sealed interface BookEditNavAction {
    data object NavigateBack : BookEditNavAction

    /** Display a transient success snackbar (typically after Save). */
    data class ShowSaveSuccess(
        val message: String,
    ) : BookEditNavAction
}
