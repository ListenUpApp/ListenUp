package com.calypsan.listenup.client.data.local.db

import androidx.room.TypeConverter
import com.calypsan.listenup.api.sync.BioEntryMode
import com.calypsan.listenup.api.sync.EntityKind
import com.calypsan.listenup.api.sync.UserEditedField
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.ContributorId
import com.calypsan.listenup.core.FolderId
import com.calypsan.listenup.core.LibraryId
import com.calypsan.listenup.core.SeriesId
import com.calypsan.listenup.core.Timestamp
import com.calypsan.listenup.api.dto.auth.UserId
import com.calypsan.listenup.core.appJson
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer

/**
 * Room type converters for value classes.
 *
 * Converts inline value classes (BookId, Timestamp) to/from their underlying
 * primitive types for database storage. These converters enable type-safe
 * value classes with zero runtime overhead while maintaining Room compatibility.
 */
internal class ValueClassConverters {
    /**
     * Convert BookId value class to String for database storage.
     */
    @TypeConverter
    fun fromBookId(value: BookId): String = value.value

    /**
     * Convert String from database to BookId value class.
     */
    @TypeConverter
    fun toBookId(value: String): BookId = BookId(value)

    /**
     * Convert Timestamp value class to Long for database storage.
     */
    @TypeConverter
    fun fromTimestamp(value: Timestamp): Long = value.epochMillis

    /**
     * Convert Long from database to Timestamp value class.
     */
    @TypeConverter
    fun toTimestamp(value: Long): Timestamp = Timestamp(value)

    /**
     * Convert nullable Timestamp value class to nullable Long for database storage.
     */
    @TypeConverter
    fun fromNullableTimestamp(value: Timestamp?): Long? = value?.epochMillis

    /**
     * Convert nullable Long from database to nullable Timestamp value class.
     */
    @TypeConverter
    fun toNullableTimestamp(value: Long?): Timestamp? = value?.let { Timestamp(it) }

    /**
     * Convert SeriesId value class to String for database storage.
     */
    @TypeConverter
    fun seriesIdToString(id: SeriesId?): String? = id?.value

    /**
     * Convert String from database to SeriesId value class.
     */
    @TypeConverter
    fun stringToSeriesId(value: String?): SeriesId? = value?.let { SeriesId(it) }

    /**
     * Convert ContributorId value class to String for database storage.
     */
    @TypeConverter
    fun contributorIdToString(id: ContributorId?): String? = id?.value

    /**
     * Convert String from database to ContributorId value class.
     */
    @TypeConverter
    fun stringToContributorId(value: String?): ContributorId? = value?.let { ContributorId(it) }

    /**
     * Convert UserId value class to String for database storage.
     */
    @TypeConverter
    fun userIdToString(id: UserId?): String? = id?.value

    /**
     * Convert String from database to UserId value class.
     */
    @TypeConverter
    fun stringToUserId(value: String?): UserId? = value?.let { UserId(it) }

    /**
     * Convert LibraryId value class to String for database storage.
     */
    @TypeConverter
    fun libraryIdToString(id: LibraryId): String = id.value

    /**
     * Convert String from database to LibraryId value class.
     */
    @TypeConverter
    fun stringToLibraryId(value: String): LibraryId = LibraryId(value)

    /**
     * Convert FolderId value class to String for database storage.
     */
    @TypeConverter
    fun folderIdToString(id: FolderId): String = id.value

    /**
     * Convert String from database to FolderId value class.
     */
    @TypeConverter
    fun stringToFolderId(value: String): FolderId = FolderId(value)
}

/**
 * Room type converters for sync-state and download-state enums.
 *
 * Stores enums by their declared `name` instead of their ordinal index — ordinal
 * storage silently corrupts data when an enum constant is inserted, reordered,
 * or removed, since previously-written rows then map to the wrong case.
 * Storing by name is resilient to reorderings and makes the
 * SQLite column human-readable in ad-hoc inspection. `valueOf` throws on an
 * unknown value, which is what we want: the app refuses to interpret a state
 * it no longer understands rather than silently remap it.
 */
internal class Converters {
    @TypeConverter
    fun fromSyncState(value: SyncState): String = value.name

    @TypeConverter
    fun toSyncState(value: String): SyncState = SyncState.valueOf(value)

    @TypeConverter
    fun fromDownloadState(state: DownloadState): String = state.name

    @TypeConverter
    fun toDownloadState(value: String): DownloadState = DownloadState.valueOf(value)
}

/**
 * Download state for tracking individual audio file downloads.
 *
 * Stored by name (not ordinal), so member order is not a wire concern.
 */
internal enum class DownloadState {
    QUEUED, // Waiting to start
    DOWNLOADING, // In progress
    PAUSED, // User paused or interrupted
    COMPLETED, // Successfully downloaded
    FAILED, // Error occurred
    DELETED, // User explicitly deleted - files removed, don't auto-download
    CANCELLED, // User cancelled; distinct from PAUSED so resumeIncompleteDownloads skips it
}

/**
 * Room type converter for [CoverDownloadStatus] enum.
 * Uses string names (not ordinals) for readable queries and forward compatibility.
 */
internal class CoverDownloadStatusConverter {
    @TypeConverter
    fun fromStatus(value: CoverDownloadStatus): String = value.name

    @TypeConverter
    fun toStatus(value: String): CoverDownloadStatus = CoverDownloadStatus.valueOf(value)
}

/**
 * Room type converters for [EntityKind] and [BioEntryMode] — the wire enums on [EntityEntity] /
 * [BioEntryEntity] (Story World Stage 2). Stores by declared `name`, not ordinal, matching
 * [Converters]' store-by-name discipline: resilient to a future enum-member reordering, and
 * `valueOf` throws on an unknown value rather than silently remapping it.
 */
internal class EntityEnumConverters {
    @TypeConverter
    fun fromEntityKind(value: EntityKind): String = value.name

    @TypeConverter
    fun toEntityKind(value: String): EntityKind = EntityKind.valueOf(value)

    @TypeConverter
    fun fromBioEntryMode(value: BioEntryMode): String = value.name

    @TypeConverter
    fun toBioEntryMode(value: String): BioEntryMode = BioEntryMode.valueOf(value)
}

/**
 * Room type converter for the [UserEditedField] provenance set on [BookEntity].
 *
 * Stores the set as a comma-joined list of enum `name`s in declaration order — empty string is the
 * empty set. Mirrors [Converters]' store-by-name discipline: declaration order makes the column value
 * canonical (the same set always serializes identically), and names survive enum reordering where
 * ordinals would silently corrupt. `valueOf` throws on an unknown token, so the app refuses to
 * interpret provenance it no longer understands rather than dropping a user's rescan protection.
 */
internal class UserEditedFieldsConverter {
    @TypeConverter
    fun fromSet(value: Set<UserEditedField>): String =
        UserEditedField.entries.filter { it in value }.joinToString(",") { it.name }

    @TypeConverter
    fun toSet(value: String): Set<UserEditedField> =
        if (value.isEmpty()) {
            emptySet()
        } else {
            value.split(",").mapTo(mutableSetOf()) { UserEditedField.valueOf(it) }
        }
}

/**
 * Round-trips a small `List<String>` as a JSON array using [appJson].
 *
 * Replaces the deleted `StringListConverter`: the old split-on-`|||`
 * approach silently corrupted any entry that happened to contain the literal
 * delimiter. JSON has no such collision surface and is human-readable in ad-hoc
 * database inspection.
 *
 * Intended for short, bounded lists (shelf cover previews, etc.) where a
 * columnar approach or junction table would be over-engineering. Do not use
 * for unbounded collections — those still belong in a separate table.
 */
internal class StringListJsonConverter {
    @TypeConverter
    fun fromList(value: List<String>): String = appJson.encodeToString(ListSerializer(String.serializer()), value)

    @TypeConverter
    fun toList(value: String): List<String> =
        if (value.isEmpty()) {
            emptyList()
        } else {
            appJson.decodeFromString(ListSerializer(String.serializer()), value)
        }
}
