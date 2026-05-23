package com.calypsan.listenup.client.data.repository

import com.calypsan.listenup.client.data.local.db.UserProfileDao
import com.calypsan.listenup.client.data.local.db.UserProfileEntity
import com.calypsan.listenup.client.domain.model.CachedUserProfile
import com.calypsan.listenup.client.domain.repository.UserProfileRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Implementation of UserProfileRepository backed by Room database.
 */
class UserProfileRepositoryImpl(
    private val userProfileDao: UserProfileDao,
) : UserProfileRepository {
    override suspend fun getById(userId: String): CachedUserProfile? = userProfileDao.getById(userId)?.toDomain()

    override fun observeProfile(userId: String): Flow<CachedUserProfile?> =
        userProfileDao.observeById(userId).map { it?.toDomain() }
}

/**
 * Map entity to domain model.
 */
private fun UserProfileEntity.toDomain(): CachedUserProfile =
    CachedUserProfile(
        id = id,
        displayName = displayName,
        avatarType = avatarType,
        avatarValue = avatarValue,
        avatarColor = avatarColor,
    )
