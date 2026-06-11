package com.calypsan.listenup.client.presentation.admin

import com.calypsan.listenup.client.data.remote.DirectoryEntryResponse
import com.calypsan.listenup.client.data.remote.SearchHitResponse
import com.calypsan.listenup.client.data.remote.UserSearchResult
import com.calypsan.listenup.client.data.remote.model.ABSBookMatch
import com.calypsan.listenup.client.data.remote.model.ABSUserMatch
import com.calypsan.listenup.core.FileSource

/**
 * Source type for the ABS backup file.
 */
enum class ABSSourceType {
    /** File from user's device (phone/tablet) */
    LOCAL,

    /** File already on the server */
    REMOTE,
}

/**
 * Step in the ABS import wizard.
 */
enum class ABSImportStep {
    /** Choose between local file or server file */
    SOURCE_SELECTION,

    /** Browsing server filesystem (remote mode) */
    FILE_BROWSER,

    /** Uploading local file to server */
    UPLOADING,

    /** Analyzing the backup */
    ANALYZING,

    /** Mapping ABS users to ListenUp users */
    USER_MAPPING,

    /** Mapping ABS books to ListenUp books */
    BOOK_MAPPING,

    /** Configuring import options */
    IMPORT_OPTIONS,

    /** Import in progress */
    IMPORTING,

    /** Import complete, showing results */
    RESULTS,
}

/**
 * Tab selection for user mapping step.
 */
enum class UserMappingTab {
    /** Users that need manual mapping */
    NEEDS_REVIEW,

    /** Auto-matched users for verification */
    AUTO_MATCHED,
}

/**
 * Tab selection for book mapping step.
 */
enum class BookMappingTab {
    /** Books that need manual mapping */
    NEEDS_REVIEW,

    /** Auto-matched books for verification */
    AUTO_MATCHED,
}

/**
 * Display information for a selected user mapping.
 */
data class SelectedUserDisplay(
    val userId: String,
    val email: String,
    val displayName: String? = null,
)

/**
 * Display information for a selected book mapping.
 */
data class SelectedBookDisplay(
    val bookId: String,
    val title: String,
    val author: String? = null,
    val durationMs: Long? = null,
)

/**
 * UI state for the ABS import wizard.
 *
 * Sealed hierarchy:
 * - [Loading] declared for future async-init hooks; the current VM has no initial
 *   load and starts directly in [Ready].
 * - [Ready] carries the full wizard state: the current [ABSImportStep], every
 *   pipeline's inputs/outputs, overlays (`isUploading`, `isAnalyzing`,
 *   `isLoadingDirectories`, `isSearchingUsers`, `isSearchingBooks`, `isImporting`,
 *   plus item-level loading ids), and intent fields (tab selections, active search
 *   ids, search queries, import-option toggles, mapping displays). A transient
 *   `error` field surfaces mutation failures as snackbar-style banners.
 * - [Error] terminal state declared for parity with other W5 migrations; no code
 *   path currently reaches it, since pipeline failures fall back to
 *   [ABSImportStep.SOURCE_SELECTION] with a transient `error` on [Ready].
 *
 * W5 minimal-flatten note: `ABSImportStep` already discriminates the wizard
 * phase and the fields it carries overlap across adjacent steps (e.g. back-nav
 * from `BOOK_MAPPING` consults `usersPending`). Splitting [Ready] into per-step
 * sub-records would duplicate shared data; decomposition is deferred to W6.
 */
sealed interface ABSImportUiState {
    data object Loading : ABSImportUiState

    /**
     * Wizard is interactive. [step] discriminates the current phase; the remaining fields
     * carry pipeline inputs/outputs, action overlays, and a transient `error` for snackbar
     * surfacing. See the parent KDoc for the W5 minimal-flatten note.
     */
    @Suppress("LongParameterList")
    data class Ready(
        val step: ABSImportStep = ABSImportStep.SOURCE_SELECTION,
        val sourceType: ABSSourceType? = null,
        // Local file state
        val selectedLocalFile: SelectedLocalFile? = null,
        val isUploading: Boolean = false,
        val uploadProgress: Float = 0f,
        // Remote file browser state
        val currentPath: String = "/",
        val parentPath: String? = null,
        val directories: List<DirectoryEntryResponse> = emptyList(),
        val isLoadingDirectories: Boolean = false,
        val isRoot: Boolean = true,
        val selectedRemotePath: String = "",
        // After file is selected/uploaded
        val backupPath: String = "",
        val isAnalyzing: Boolean = false,
        val analysisComplete: Boolean = false,
        val analyzePhase: String = "",
        val analyzeCurrent: Int = 0,
        val analyzeTotal: Int = 0,
        // Analysis results
        val summary: String = "",
        val totalUsers: Int = 0,
        val totalBooks: Int = 0,
        val totalSessions: Int = 0,
        val usersMatched: Int = 0,
        val usersPending: Int = 0,
        val booksMatched: Int = 0,
        val booksPending: Int = 0,
        val sessionsReady: Int = 0,
        val sessionsPending: Int = 0,
        val progressReady: Int = 0,
        val progressPending: Int = 0,
        val userMatches: List<ABSUserMatch> = emptyList(),
        val bookMatches: List<ABSBookMatch> = emptyList(),
        val analysisWarnings: List<String> = emptyList(),
        // User/book mappings - ABS ID -> ListenUp ID
        val userMappings: Map<String, String> = emptyMap(),
        val bookMappings: Map<String, String> = emptyMap(),
        // User mapping UI state
        val userMappingTab: UserMappingTab = UserMappingTab.NEEDS_REVIEW,
        // Display info for selected users (absUserId -> display info)
        val selectedUserDisplays: Map<String, SelectedUserDisplay> = emptyMap(),
        // Inline user search state (for the currently active search field)
        val activeSearchAbsUserId: String? = null,
        val userSearchQuery: String = "",
        val userSearchResults: List<UserSearchResult> = emptyList(),
        val isSearchingUsers: Boolean = false,
        // ID of the user result item currently being processed (shows loading spinner)
        val loadingUserItemId: String? = null,
        // Book mapping UI state
        val bookMappingTab: BookMappingTab = BookMappingTab.NEEDS_REVIEW,
        // Display info for selected books (absItemId -> display info)
        val selectedBookDisplays: Map<String, SelectedBookDisplay> = emptyMap(),
        // Inline book search state (for the currently active search field)
        val activeSearchAbsItemId: String? = null,
        val bookSearchQuery: String = "",
        val bookSearchResults: List<SearchHitResponse> = emptyList(),
        val isSearchingBooks: Boolean = false,
        // ID of the book result item currently being processed (shows loading spinner)
        val loadingBookItemId: String? = null,
        // Import options
        val importSessions: Boolean = true,
        val importProgress: Boolean = true,
        val rebuildProgress: Boolean = true,
        // Import results
        val isImporting: Boolean = false,
        val importResults: ABSImportResults? = null,
        // Transient mutation-failure error (snackbar/banner).
        val error: String? = null,
    ) : ABSImportUiState

    /** Terminal failure state declared for parity with other W5 migrations; not currently reached. */
    data class Error(
        val message: String,
    ) : ABSImportUiState
}

/**
 * A locally selected file ready for upload.
 *
 * Uses [FileSource] for streaming access to avoid loading the entire file into memory.
 * This is critical for large backup files that could otherwise cause OOM crashes.
 */
data class SelectedLocalFile(
    val fileSource: FileSource,
    val filename: String,
    val size: Long,
)

/**
 * Results from the ABS import.
 */
data class ABSImportResults(
    val sessionsImported: Int,
    val sessionsSkipped: Int,
    val progressImported: Int,
    val progressSkipped: Int,
    val eventsCreated: Int,
    val affectedUsers: Int,
    val duration: String,
    val warnings: List<String>,
    val errors: List<String>,
)
