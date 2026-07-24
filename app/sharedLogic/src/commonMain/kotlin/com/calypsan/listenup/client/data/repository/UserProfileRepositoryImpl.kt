package com.calypsan.listenup.client.data.repository

import com.calypsan.listenup.client.data.local.db.PublicProfileDao
import com.calypsan.listenup.client.data.local.db.PublicProfileEntity
import com.calypsan.listenup.client.domain.model.CachedUserProfile
import com.calypsan.listenup.client.domain.repository.UserProfileRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Implementation of [UserProfileRepository] backed by the synced `public_profiles` roster.
 *
 * Reads from [PublicProfileDao] — the server-synced social roster that already powers the
 * leaderboard — so it resolves EVERY user, including the signed-in one. This is what lets an
 * avatar render a real name/color/photo for any user, not only the current user (whose profile
 * used to be the only row ever written to the local cache).
 */
internal class UserProfileRepositoryImpl(
    private val publicProfileDao: PublicProfileDao,
) : UserProfileRepository {
    override suspend fun getById(userId: String): CachedUserProfile? =
        publicProfileDao.findById(userId)?.takeIf { it.deletedAt == null }?.toDomain()

    override fun observeProfile(userId: String): Flow<CachedUserProfile?> =
        publicProfileDao.observeById(userId).map { it?.toDomain() }
}

/**
 * Map a synced public-profile row to the lightweight avatar model.
 *
 * The avatar color is not carried: [PublicProfileEntity] holds no server-assigned hex, so the
 * avatar renderer derives a stable per-user color from the id. [PublicProfileEntity.avatarUpdatedAt]
 * maps to [CachedUserProfile.updatedAt] so the Coil cache key versions on avatar-byte changes —
 * a re-uploaded avatar busts the stale bitmap.
 */
private fun PublicProfileEntity.toDomain(): CachedUserProfile =
    CachedUserProfile(
        id = id,
        displayName = displayName,
        avatarType = avatarType,
        updatedAt = avatarUpdatedAt,
    )
