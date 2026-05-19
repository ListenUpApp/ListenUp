package com.calypsan.listenup.client.data.repository

import com.calypsan.listenup.core.Success
import com.calypsan.listenup.core.Timestamp
import com.calypsan.listenup.api.dto.auth.UserId
import com.calypsan.listenup.client.data.local.db.UserDao
import com.calypsan.listenup.client.data.local.db.UserEntity
import com.calypsan.listenup.client.data.remote.CurrentUserResponse
import com.calypsan.listenup.client.data.remote.SessionApiContract
import com.calypsan.listenup.client.domain.model.User
import com.calypsan.listenup.client.domain.repository.UserRepository
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val logger = KotlinLogging.logger {}

/**
 * Implementation of UserRepository that uses Room for persistence.
 *
 * Wraps UserDao and converts entities to domain models, keeping
 * persistence concerns in the data layer while exposing clean
 * domain types to ViewModels.
 *
 * @property userDao Room DAO for user operations
 * @property sessionApi API for fetching user profile from server
 */
class UserRepositoryImpl(
    private val userDao: UserDao,
    private val sessionApi: SessionApiContract,
) : UserRepository {
    override fun observeCurrentUser(): Flow<User?> = userDao.observeCurrentUser().map { it?.toDomain() }

    override fun observeIsAdmin(): Flow<Boolean> =
        userDao.observeCurrentUser().map { user ->
            user?.isRoot == true
        }

    override suspend fun getCurrentUser(): User? = userDao.getCurrentUser()?.toDomain()

    override suspend fun saveUser(user: User) {
        userDao.upsert(user.toEntity())
    }

    override suspend fun clearUsers() {
        userDao.clear()
    }

    override suspend fun refreshCurrentUser(): User? {
        logger.debug { "Refreshing current user from server" }
        return when (val result = sessionApi.getCurrentUser()) {
            is Success -> {
                val user = result.data.toDomain()
                saveUser(user)
                logger.info { "User data refreshed: ${user.email}" }
                user
            }

            else -> {
                logger.warn { "Failed to refresh current user from server" }
                null
            }
        }
    }
}

/**
 * Convert UserEntity to User domain model.
 *
 * Maps isRoot to isAdmin for cleaner domain semantics.
 */
private fun UserEntity.toDomain(): User =
    User(
        id = id,
        email = email,
        displayName = displayName,
        firstName = firstName,
        lastName = lastName,
        isAdmin = isRoot,
        avatarType = avatarType,
        avatarValue = avatarValue,
        avatarColor = avatarColor,
        tagline = tagline,
        createdAtMs = createdAt.epochMillis,
        updatedAtMs = updatedAt.epochMillis,
    )

/**
 * Convert CurrentUserResponse API model to User domain model.
 */
private fun CurrentUserResponse.toDomain(): User =
    User(
        id = UserId(id),
        email = email,
        displayName = displayName,
        firstName = firstName,
        lastName = lastName,
        isAdmin = isRoot,
        avatarType = avatarType,
        avatarValue = avatarValue,
        avatarColor = avatarColor,
        tagline = null, // API doesn't return tagline for current user
        createdAtMs = createdAt,
        updatedAtMs = updatedAt,
    )

/**
 * Convert User domain model to UserEntity for persistence.
 *
 * Maps isAdmin back to isRoot for database storage.
 */
private fun User.toEntity(): UserEntity =
    UserEntity(
        id = id,
        email = email,
        displayName = displayName,
        firstName = firstName,
        lastName = lastName,
        isRoot = isAdmin,
        avatarType = avatarType,
        avatarValue = avatarValue,
        avatarColor = avatarColor,
        tagline = tagline,
        createdAt = Timestamp(createdAtMs),
        updatedAt = Timestamp(updatedAtMs),
    )
