package com.calypsan.listenup.server.api

import com.calypsan.listenup.api.ProfileService
import com.calypsan.listenup.api.dto.auth.UserId
import com.calypsan.listenup.api.dto.profile.Profile
import com.calypsan.listenup.api.dto.profile.UpdateProfileRequest
import com.calypsan.listenup.api.error.AuthError
import com.calypsan.listenup.api.error.ProfileError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.server.auth.PasswordHasher
import com.calypsan.listenup.server.auth.PrincipalProvider
import com.calypsan.listenup.server.db.UserEntity
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction

/**
 * Server-side implementation of [ProfileService].
 *
 * Both operations are scoped to the authenticated principal. Mutations apply only
 * the non-null fields from the request, so a caller that sends only [UpdateProfileRequest.tagline]
 * leaves [UpdateProfileRequest.displayName] and [UpdateProfileRequest.avatarType] untouched.
 *
 * Password changes verify the current password inside the transaction (against the stored hash)
 * before writing the new hash. The new hash is computed outside the transaction so the
 * CPU-bound Argon2 work does not hold a DB connection.
 *
 * Route handlers call [copyWith] to bind each request to the authenticated principal; the
 * Koin singleton carries [PrincipalProvider.None] so an un-scoped call is denied rather than
 * running unauthenticated.
 */
internal class ProfileServiceImpl(
    private val db: Database,
    private val passwordHasher: PasswordHasher,
    private val principal: PrincipalProvider = PrincipalProvider.None,
) : ProfileService {
    override suspend fun getMyProfile(): AppResult<Profile> {
        val userId =
            principal.current()?.userId?.value
                ?: return AppResult.Failure(AuthError.PermissionDenied())
        return suspendTransaction(db) {
            val u =
                UserEntity.findById(userId)
                    ?: return@suspendTransaction AppResult.Failure(AuthError.PermissionDenied())
            AppResult.Success(u.toProfile())
        }
    }

    override suspend fun updateMyProfile(request: UpdateProfileRequest): AppResult<Profile> {
        val userId =
            principal.current()?.userId?.value
                ?: return AppResult.Failure(AuthError.PermissionDenied())
        // Hash outside the transaction — Argon2 is CPU-bound and must not hold a DB connection.
        val newHash = request.password?.let { passwordHasher.hash(it.newPassword) }
        return suspendTransaction(db) {
            val u =
                UserEntity.findById(userId)
                    ?: return@suspendTransaction AppResult.Failure(AuthError.PermissionDenied())
            request.password?.let { pc ->
                if (!passwordHasher.verify(pc.currentPassword, u.passwordHash)) {
                    return@suspendTransaction AppResult.Failure(ProfileError.WrongPassword())
                }
                u.passwordHash = newHash!!
            }
            request.displayName?.let { u.displayName = it }
            request.tagline?.let { u.tagline = it }
            request.avatarType?.let { u.avatarType = it }
            u.updatedAt = System.currentTimeMillis()
            AppResult.Success(u.toProfile())
        }
    }

    /** Returns a copy scoped to the given [principal]. Route handlers call this per-request. */
    fun copyWith(principal: PrincipalProvider): ProfileServiceImpl =
        ProfileServiceImpl(db = db, passwordHasher = passwordHasher, principal = principal)

    private fun UserEntity.toProfile(): Profile =
        Profile(
            userId = UserId(id.value),
            displayName = displayName,
            tagline = tagline,
            avatarType = avatarType,
            updatedAt = updatedAt,
        )
}

/**
 * Public factory for tests that need a [ProfileService] without going through Koin.
 *
 * Mirrors [createBookService] / [createGenreService] — cross-module test harnesses in
 * `:sharedLogic:jvmTest` use this to build the service without piercing the `internal`
 * access on [ProfileServiceImpl]. Production wiring constructs the impl directly inside
 * [com.calypsan.listenup.server.di.profileModule].
 */
fun createProfileService(
    db: Database,
    passwordHasher: PasswordHasher,
): ProfileService = ProfileServiceImpl(db = db, passwordHasher = passwordHasher)

/**
 * Scopes a [ProfileService] built by [createProfileService] to [principal] for one request.
 *
 * Public so cross-module test harnesses can bind the authenticated caller without piercing
 * the `internal` access on [ProfileServiceImpl.copyWith]. Production wiring calls
 * [ProfileServiceImpl.copyWith] directly in the RPC route via [registerScoped].
 */
fun profileServiceScopedTo(
    service: ProfileService,
    principal: PrincipalProvider,
): ProfileService = (service as ProfileServiceImpl).copyWith(principal)
