package com.calypsan.listenup.server.sync

import com.calypsan.listenup.api.sync.PublicProfileSyncPayload
import com.calypsan.listenup.server.db.PublicProfilesTable
import kotlin.time.Clock
import kotlinx.serialization.KSerializer
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update

/**
 * Syncable repository for the global `public_profiles` projection.
 *
 * Global (`userScoped` defaults `false`) — every client receives every user's row.
 * Single-table; the maintainer assembles the full payload, so [writePayload] is a
 * straight INSERT/UPDATE of all columns. Mirrors [TagRepository].
 */
class PublicProfileRepository(
    db: Database,
    bus: ChangeBus,
    registry: SyncRegistry,
    clock: Clock = Clock.System,
) : SyncableRepository<PublicProfileSyncPayload, String>(
        db,
        PublicProfilesTable,
        bus,
        registry,
        "public_profiles",
        clock,
    ) {
    override val elementSerializer: KSerializer<PublicProfileSyncPayload> =
        PublicProfileSyncPayload.serializer()

    override val PublicProfileSyncPayload.id: String get() = this.id

    override fun PublicProfileSyncPayload.revisionOf(): Long = revision

    override suspend fun readPayload(idStr: String): PublicProfileSyncPayload? =
        PublicProfilesTable
            .selectAll()
            .where { PublicProfilesTable.id eq idStr }
            .firstOrNull()
            ?.let { row ->
                PublicProfileSyncPayload(
                    id = row[PublicProfilesTable.id],
                    displayName = row[PublicProfilesTable.displayName],
                    avatarType = row[PublicProfilesTable.avatarType],
                    tagline = row[PublicProfilesTable.tagline],
                    totalSecondsAllTime = row[PublicProfilesTable.totalSecondsAllTime],
                    totalSecondsLast7Days = row[PublicProfilesTable.totalSecondsLast7Days],
                    totalSecondsLast30Days = row[PublicProfilesTable.totalSecondsLast30Days],
                    totalSecondsLast365Days = row[PublicProfilesTable.totalSecondsLast365Days],
                    booksFinished = row[PublicProfilesTable.booksFinished],
                    currentStreakDays = row[PublicProfilesTable.currentStreakDays],
                    longestStreakDays = row[PublicProfilesTable.longestStreakDays],
                    revision = row[PublicProfilesTable.revision],
                    updatedAt = row[PublicProfilesTable.updatedAt],
                    createdAt = row[PublicProfilesTable.createdAt],
                    deletedAt = row[PublicProfilesTable.deletedAt],
                )
            }

    override suspend fun writePayload(
        value: PublicProfileSyncPayload,
        rev: Long,
        now: Long,
        clientOpId: String?,
        userId: String?,
        existed: Boolean,
    ) {
        if (existed) {
            PublicProfilesTable.update({ PublicProfilesTable.id eq value.id }) { stmt ->
                stmt[PublicProfilesTable.displayName] = value.displayName
                stmt[PublicProfilesTable.avatarType] = value.avatarType
                stmt[PublicProfilesTable.tagline] = value.tagline
                stmt[PublicProfilesTable.totalSecondsAllTime] = value.totalSecondsAllTime
                stmt[PublicProfilesTable.totalSecondsLast7Days] = value.totalSecondsLast7Days
                stmt[PublicProfilesTable.totalSecondsLast30Days] = value.totalSecondsLast30Days
                stmt[PublicProfilesTable.totalSecondsLast365Days] = value.totalSecondsLast365Days
                stmt[PublicProfilesTable.booksFinished] = value.booksFinished
                stmt[PublicProfilesTable.currentStreakDays] = value.currentStreakDays
                stmt[PublicProfilesTable.longestStreakDays] = value.longestStreakDays
                stmt[PublicProfilesTable.revision] = rev
                stmt[PublicProfilesTable.updatedAt] = now
                stmt[PublicProfilesTable.deletedAt] = null
                stmt[PublicProfilesTable.clientOpId] = clientOpId
            }
        } else {
            PublicProfilesTable.insert { stmt ->
                stmt[PublicProfilesTable.id] = value.id
                stmt[PublicProfilesTable.displayName] = value.displayName
                stmt[PublicProfilesTable.avatarType] = value.avatarType
                stmt[PublicProfilesTable.tagline] = value.tagline
                stmt[PublicProfilesTable.totalSecondsAllTime] = value.totalSecondsAllTime
                stmt[PublicProfilesTable.totalSecondsLast7Days] = value.totalSecondsLast7Days
                stmt[PublicProfilesTable.totalSecondsLast30Days] = value.totalSecondsLast30Days
                stmt[PublicProfilesTable.totalSecondsLast365Days] = value.totalSecondsLast365Days
                stmt[PublicProfilesTable.booksFinished] = value.booksFinished
                stmt[PublicProfilesTable.currentStreakDays] = value.currentStreakDays
                stmt[PublicProfilesTable.longestStreakDays] = value.longestStreakDays
                stmt[PublicProfilesTable.revision] = rev
                stmt[PublicProfilesTable.createdAt] = now
                stmt[PublicProfilesTable.updatedAt] = now
                stmt[PublicProfilesTable.deletedAt] = null
                stmt[PublicProfilesTable.clientOpId] = clientOpId
            }
        }
    }
}
