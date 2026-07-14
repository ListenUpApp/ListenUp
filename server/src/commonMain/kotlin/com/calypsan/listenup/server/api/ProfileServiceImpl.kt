package com.calypsan.listenup.server.api

import com.calypsan.listenup.api.ProfileService
import com.calypsan.listenup.api.dto.auth.UserId
import com.calypsan.listenup.api.dto.profile.Profile
import com.calypsan.listenup.api.dto.profile.UpdateProfileRequest
import com.calypsan.listenup.api.error.AuthError
import com.calypsan.listenup.api.error.ProfileError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.server.auth.Argon2Limiter
import com.calypsan.listenup.server.auth.PrincipalProvider
import com.calypsan.listenup.server.db.sqldelight.ListenUpDatabase
import com.calypsan.listenup.server.db.sqldelight.Users
import com.calypsan.listenup.server.db.sqldelight.suspendTransaction
import com.calypsan.listenup.server.media.ImageStore
import com.calypsan.listenup.server.services.PublicProfileMaintainer
import kotlin.time.Clock

private const val AVATAR_TYPE_AUTO = "auto"

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
    private val sql: ListenUpDatabase,
    private val argon2Limiter: Argon2Limiter,
    private val publicProfileMaintainer: PublicProfileMaintainer,
    private val imageStore: ImageStore,
    private val clock: Clock = Clock.System,
    private val principal: PrincipalProvider = PrincipalProvider.None,
) : ProfileService {
    override suspend fun getMyProfile(): AppResult<Profile> {
        val userId =
            principal.current()?.userId?.value
                ?: return AppResult.Failure(AuthError.PermissionDenied())
        return suspendTransaction(sql) {
            val u =
                sql.usersQueries.selectById(userId).executeAsOneOrNull()
                    ?: return@suspendTransaction AppResult.Failure(AuthError.PermissionDenied())
            AppResult.Success(u.toProfile())
        }
    }

    override suspend fun updateMyProfile(request: UpdateProfileRequest): AppResult<Profile> {
        val userId =
            principal.current()?.userId?.value
                ?: return AppResult.Failure(AuthError.PermissionDenied())
        // Hash outside the transaction — Argon2 is CPU-bound and must not hold a DB connection.
        val newHash = request.password?.let { argon2Limiter.hash(it.newPassword) }
        // Read the current row first. The password verify and the write are split across two
        // steps because the SQLDelight transaction body is non-suspend and cannot call the
        // suspend Argon2 verify — matching the established cutover shape.
        val current: Users =
            suspendTransaction(sql) { sql.usersQueries.selectById(userId).executeAsOneOrNull() }
                ?: return AppResult.Failure(AuthError.PermissionDenied())

        // Verify the current password (suspend Argon2) outside any transaction.
        request.password?.let { pc ->
            if (!argon2Limiter.verify(pc.currentPassword, current.password_hash)) {
                return AppResult.Failure(ProfileError.WrongPassword())
            }
        }

        // Merge only the non-null request fields, then write the merged row back.
        val mergedDisplayName = request.displayName ?: current.display_name
        val mergedTagline = request.tagline ?: current.tagline
        val mergedAvatarType = request.avatarType ?: current.avatar_type
        val mergedHash = newHash ?: current.password_hash
        val now = clock.now().toEpochMilliseconds()
        // The avatar version advances only when the avatar type actually flips (the sole self-service
        // change here is revert-to-auto; upload flips to "image" via the REST route). Bumping it feeds
        // the public_profiles projection so every client's cached bitmap busts — otherwise a revert
        // would silently never propagate (the bug this closes).
        val avatarChanged = mergedAvatarType != current.avatar_type
        val mergedAvatarUpdatedAt = if (avatarChanged) now else current.avatar_updated_at
        suspendTransaction(sql) {
            sql.usersQueries.updateProfileFields(
                display_name = mergedDisplayName,
                tagline = mergedTagline,
                avatar_type = mergedAvatarType,
                avatar_updated_at = mergedAvatarUpdatedAt,
                password_hash = mergedHash,
                updated_at = now,
                id = userId,
            )
        }
        // Reverting to the auto avatar orphans the stored bytes — delete them so GET /avatars/{id}
        // 404s and no stale image lingers on disk. Idempotent; runs outside the transaction.
        if (avatarChanged && mergedAvatarType == AVATAR_TYPE_AUTO) {
            imageStore.delete(userId)
        }
        // Refresh the projection after the user-row write commits — reads back from DB.
        publicProfileMaintainer.refreshBestEffort(userId)
        return AppResult.Success(
            Profile(
                userId = UserId(userId),
                displayName = mergedDisplayName,
                tagline = mergedTagline,
                avatarType = mergedAvatarType,
                updatedAt = now,
            ),
        )
    }

    /** Returns a copy scoped to the given [principal]. Route handlers call this per-request. */
    fun copyWith(principal: PrincipalProvider): ProfileServiceImpl =
        ProfileServiceImpl(
            sql = sql,
            argon2Limiter = argon2Limiter,
            publicProfileMaintainer = publicProfileMaintainer,
            imageStore = imageStore,
            clock = clock,
            principal = principal,
        )

    private fun Users.toProfile(): Profile =
        Profile(
            userId = UserId(id),
            displayName = display_name,
            tagline = tagline,
            avatarType = avatar_type,
            updatedAt = updated_at,
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
    sql: ListenUpDatabase,
    argon2Limiter: Argon2Limiter,
    publicProfileMaintainer: PublicProfileMaintainer,
    imageStore: ImageStore,
): ProfileService =
    ProfileServiceImpl(
        sql = sql,
        argon2Limiter = argon2Limiter,
        publicProfileMaintainer = publicProfileMaintainer,
        imageStore = imageStore,
    )

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
