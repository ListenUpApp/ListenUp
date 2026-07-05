package com.calypsan.listenup.client.data.repository

import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.core.Timestamp
import kotlin.coroutines.cancellation.CancellationException
import com.calypsan.listenup.client.data.local.db.UserDao
import com.calypsan.listenup.client.data.local.db.UserEntity
import com.calypsan.listenup.client.data.remote.AuthRpcFactory
import com.calypsan.listenup.client.domain.model.User
import com.calypsan.listenup.client.domain.model.toDomain
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
 * @property authRpcFactory Supplies the bearer-gated [AuthServiceAuthed] RPC proxy used to
 *   refresh the current user's profile from the server (RPC-first; no REST fallback).
 */
internal class UserRepositoryImpl(
    private val userDao: UserDao,
    private val authRpcFactory: AuthRpcFactory,
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
        logger.debug { "Refreshing current user from server (RPC)" }
        // Opening the RPC connection can fail (e.g. no server URL configured yet). This is a
        // fallible refresh, not a fatal path: swallow to null so callers fall back to the
        // locally-persisted user instead of surfacing a hard failure. CancellationException
        // is always re-raised per structured-concurrency rules.
        return try {
            when (val result = authRpcFactory.authedService().currentUser()) {
                is AppResult.Success -> {
                    val user = result.data.toDomain()
                    saveUser(user)
                    logger.info { "User data refreshed: isAdmin=${user.isAdmin}" }
                    user
                }

                is AppResult.Failure -> {
                    logger.warn { "Failed to refresh current user via RPC: ${result.error.code}" }
                    null
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn(e) { "refreshCurrentUser failed before a result (RPC connect/transport)" }
            null
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
        tagline = tagline,
        createdAtMs = createdAt.epochMillis,
        updatedAtMs = updatedAt.epochMillis,
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
        tagline = tagline,
        createdAt = Timestamp(createdAtMs),
        updatedAt = Timestamp(updatedAtMs),
    )
