package com.calypsan.listenup.client.domain.model

import com.calypsan.listenup.api.dto.auth.UserRole
import com.calypsan.listenup.api.dto.auth.User as ContractUser

/**
 * Map a contract [ContractUser] (the auth service's identity-only shape)
 * to the client's local [User] domain model.
 *
 * The contract carries identity + role + status + createdAt; the domain
 * model adds nullable profile fields (`firstName`, `lastName`, `tagline`)
 * that other features fetch separately. We default those to null; later
 * profile-fetch paths overwrite them in Room. Avatar state is not part of
 * [User] — it lives in the synced `public_profiles` row.
 */
fun ContractUser.toDomain(): User =
    User(
        id = id,
        email = email,
        displayName = displayName,
        firstName = null,
        lastName = null,
        isAdmin = role == UserRole.ROOT || role == UserRole.ADMIN,
        tagline = null,
        createdAtMs = createdAt,
        updatedAtMs = createdAt,
    )
