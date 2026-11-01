package com.calypsan.listenup.server.api

import com.calypsan.listenup.api.ProfileService
import com.calypsan.listenup.api.dto.auth.UserId
import com.calypsan.listenup.api.dto.image.validate
import com.calypsan.listenup.api.dto.profile.Profile
import com.calypsan.listenup.api.dto.profile.UpdateProfileRequest
import com.calypsan.listenup.api.error.AuthError
import com.calypsan.listenup.api.error.ProfileError
import com.calypsan.listenup.api.error.ValidationError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.server.auth.Argon2Limiter
import com.calypsan.listenup.server.auth.OpenToAllMembers
import com.calypsan.listenup.server.auth.PasswordPolicy
import com.calypsan.listenup.server.auth.PrincipalProvider
import com.calypsan.listenup.server.auth.SessionService
import com.calypsan.listenup.server.db.sqldelight.ListenUpDatabase
import com.calypsan.listenup.server.db.sqldelight.Users
import com.calypsan.listenup.server.db.sqldelight.suspendTransaction
import com.calypsan.listenup.server.logging.loggerFor
import com.calypsan.listenup.server.media.AvatarWriteLock
import com.calypsan.listenup.server.media.ImageStore
import com.calypsan.listenup.server.services.PublicProfileMaintainer
import com.calypsan.listenup.server.services.toImageFrameColumn
import kotlin.time.Clock

private const val AVATAR_TYPE_AUTO = "auto"
private const val AVATAR_TYPE_IMAGE = "image"

/** What [ProfileServiceImpl.writeProfile] did: wrote the edit, found the user gone, or lost a password race. */
private sealed interface ProfileWrite {
    /** The edit was written; [profile] is the merged result and [avatarReverted] says the stored bytes are now orphaned. */
    data class Done(
        val profile: Profile,
        val avatarReverted: Boolean,
    ) : ProfileWrite

    /** The user row no longer exists. */
    data object Gone : ProfileWrite

    /** The stored password hash is no longer the one the caller's current password was verified against. */
    data object PasswordRaced : ProfileWrite
}

private val log = loggerFor<ProfileServiceImpl>()

/**
 * Server-side implementation of [ProfileService].
 *
 * Both operations are scoped to the authenticated principal. Mutations apply only
 * the non-null fields from the request, so a caller that sends only [UpdateProfileRequest.tagline]
 * leaves [UpdateProfileRequest.displayName] and [UpdateProfileRequest.avatarType] untouched.
 *
 * Password changes verify the current password inside the transaction (against the stored hash)
 * before writing the new hash. The new hash is computed outside the transaction so the
 * CPU-bound Argon2 work does not hold a DB connection. A successful password change revokes the
 * caller's other live sessions — a stolen refresh token must not survive a password change — while
 * sparing the session that made the request, so the caller isn't logged out of their own action.
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
    private val sessions: SessionService,
    private val clock: Clock = Clock.System,
    private val principal: PrincipalProvider = PrincipalProvider.None,
    private val avatarWriteLock: AvatarWriteLock = AvatarWriteLock(),
) : ProfileService {
    override suspend fun getMyProfile(): AppResult<Profile> {
        val callerId =
            principal.current()?.run { userId.value }
                ?: return AppResult.Failure(AuthError.PermissionDenied())
        return suspendTransaction(sql) {
            val u =
                sql.usersQueries.selectById(callerId).executeAsOneOrNull()
                    ?: return@suspendTransaction AppResult.Failure(AuthError.PermissionDenied())
            AppResult.Success(u.toProfile())
        }
    }

    @OpenToAllMembers(reason = "the caller's own profile")
    override suspend fun updateMyProfile(request: UpdateProfileRequest): AppResult<Profile> {
        val caller = principal.current() ?: return AppResult.Failure(AuthError.PermissionDenied())
        val userId = caller.userId.value
        // Only an upload produces an image avatar; an RPC claiming one would move the avatar version to bytes that
        // were never stored.
        if (request.avatarType == AVATAR_TYPE_IMAGE) {
            return AppResult.Failure(
                ValidationError(message = "Upload a photo to use one as your avatar.", field = "avatarType"),
            )
        }
        request.avatarFrame?.validate()?.let { return AppResult.Failure(it) }
        request.password?.let { pc ->
            when (val policyCheck = PasswordPolicy.validate(pc.newPassword)) {
                is AppResult.Failure -> return policyCheck
                is AppResult.Success -> Unit
            }
        }
        // Hash outside the transaction — Argon2 is CPU-bound and must not hold a DB connection.
        val newHash = request.password?.let { argon2Limiter.hash(it.newPassword) }
        // Read the current row first, only to verify the password: the SQLDelight transaction body is non-suspend and
        // cannot call the suspend Argon2 verify. The merge below never uses this row — it reads the row again inside
        // the write transaction, because an avatar upload can land during the (slow) verify.
        val verified: Users =
            suspendTransaction(sql) { sql.usersQueries.selectById(userId).executeAsOneOrNull() }
                ?: return AppResult.Failure(AuthError.PermissionDenied())

        // Verify the current password (suspend Argon2) outside any transaction.
        request.password?.let { pc ->
            if (!argon2Limiter.verify(pc.currentPassword, verified.password_hash)) {
                return AppResult.Failure(ProfileError.WrongPassword())
            }
        }

        val now = clock.now().toEpochMilliseconds()
        val written =
            avatarWriteLock.withLock(userId) {
                val write =
                    writeProfile(
                        request = request,
                        userId = userId,
                        verifiedHash = verified.password_hash,
                        newHash = newHash,
                        now = now,
                    )
                // Reverting to the auto avatar orphans the stored bytes — delete them so GET /avatars/{id} 404s and no
                // stale image lingers on disk. Idempotent; held under the lock so an upload cannot be mid-store.
                if (write is ProfileWrite.Done && write.avatarReverted) imageStore.delete(userId)
                write
            }
        val done =
            when (written) {
                ProfileWrite.Gone -> return AppResult.Failure(AuthError.PermissionDenied())

                // The password changed between the verify and the write: the verified password is no longer current.
                ProfileWrite.PasswordRaced -> return AppResult.Failure(ProfileError.WrongPassword())

                is ProfileWrite.Done -> written
            }
        // A stolen refresh token must not survive a password change (SEC-03). Spare the caller's
        // own session — they just proved they hold the current password, not an attacker riding a
        // leaked token — so this request's device isn't logged out by its own action.
        if (request.password != null) {
            sessions.revokeAllExcept(UserId(userId), caller.sessionId)
        }
        // Refresh the projection after the user-row write commits — reads back from DB.
        publicProfileMaintainer.refreshBestEffort(userId)
        return AppResult.Success(done.profile)
    }

    /**
     * Merges [request] into the row as it is NOW — read inside the write transaction — and writes it. Avatar columns
     * are written only when the request changes them; any other edit leaves them exactly as the row has them.
     * [verifiedHash] is the password hash the caller's current password was checked against.
     */
    private suspend fun writeProfile(
        request: UpdateProfileRequest,
        userId: String,
        verifiedHash: String,
        newHash: String?,
        now: Long,
    ): ProfileWrite =
        suspendTransaction(sql) {
            val fresh =
                sql.usersQueries.selectById(userId).executeAsOneOrNull() ?: return@suspendTransaction ProfileWrite.Gone
            if (request.password != null &&
                fresh.password_hash != verifiedHash
            ) {
                return@suspendTransaction ProfileWrite.PasswordRaced
            }
            val displayName = request.displayName ?: fresh.display_name
            val tagline = request.tagline ?: fresh.tagline
            val passwordHash = newHash ?: fresh.password_hash
            val avatarTouched = request.avatarType != null || request.avatarFrame != null
            var avatarType = fresh.avatar_type
            var avatarReverted = false
            if (avatarTouched) {
                avatarType = request.avatarType ?: fresh.avatar_type
                // The avatar version advances only when the avatar type actually flips (the sole self-service change
                // is revert-to-auto; upload flips to "image" via the REST route). Bumping it feeds the public_profiles
                // projection so every client's cached bitmap busts — otherwise a revert would silently never propagate.
                val avatarChanged = avatarType != fresh.avatar_type
                avatarReverted = avatarChanged && avatarType == AVATAR_TYPE_AUTO
                sql.usersQueries.updateProfileFields(
                    display_name = displayName,
                    tagline = tagline,
                    avatar_type = avatarType,
                    avatar_updated_at = if (avatarChanged) now else fresh.avatar_updated_at,
                    password_hash = passwordHash,
                    updated_at = now,
                    id = userId,
                )
                sql.usersQueries.updateAvatarFrame(
                    avatar_frame =
                        mergedAvatarFrame(
                            request = request,
                            current = fresh,
                            mergedAvatarType = avatarType,
                            avatarChanged = avatarChanged,
                        ),
                    id = userId,
                )
            } else {
                sql.usersQueries.updateNameTaglinePassword(
                    display_name = displayName,
                    tagline = tagline,
                    password_hash = passwordHash,
                    updated_at = now,
                    id = userId,
                )
            }
            ProfileWrite.Done(
                profile =
                    Profile(
                        userId = UserId(userId),
                        displayName = displayName,
                        tagline = tagline,
                        avatarType = avatarType,
                        updatedAt = now,
                    ),
                avatarReverted = avatarReverted,
            )
        }

    /** Returns a copy scoped to the given [principal]. Route handlers call this per-request. */
    fun copyWith(principal: PrincipalProvider): ProfileServiceImpl =
        ProfileServiceImpl(
            sql = sql,
            argon2Limiter = argon2Limiter,
            publicProfileMaintainer = publicProfileMaintainer,
            imageStore = imageStore,
            sessions = sessions,
            clock = clock,
            principal = principal,
            avatarWriteLock = avatarWriteLock,
        )

    /**
     * The avatar frame to keep, as its column value: none for the auto avatar; the request's when it was made for the
     * stored image (whose version is `avatar_updated_at`); otherwise the current one. A frame for an image that has since
     * been replaced is dropped and logged, never an error — the client converges on the projection.
     */
    private fun mergedAvatarFrame(
        request: UpdateProfileRequest,
        current: Users,
        mergedAvatarType: String,
        avatarChanged: Boolean,
    ): String? {
        if (mergedAvatarType == AVATAR_TYPE_AUTO) return null
        val requested = request.avatarFrame ?: return current.avatar_frame
        if (!avatarChanged && requested.imageVersion == current.avatar_updated_at.toString()) {
            return requested.toImageFrameColumn()
        }
        log.info {
            "Dropped an avatar frame for ${current.id}: made for ${requested.imageVersion}, " +
                "the avatar is ${current.avatar_updated_at}"
        }
        return current.avatar_frame
    }

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
 * `:app:sharedLogic:jvmTest` use this to build the service without piercing the `internal`
 * access on [ProfileServiceImpl]. Production wiring constructs the impl directly inside
 * [com.calypsan.listenup.server.di.profileModule].
 */
fun createProfileService(
    sql: ListenUpDatabase,
    argon2Limiter: Argon2Limiter,
    publicProfileMaintainer: PublicProfileMaintainer,
    imageStore: ImageStore,
    sessions: SessionService,
): ProfileService =
    ProfileServiceImpl(
        sql = sql,
        argon2Limiter = argon2Limiter,
        publicProfileMaintainer = publicProfileMaintainer,
        imageStore = imageStore,
        sessions = sessions,
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
