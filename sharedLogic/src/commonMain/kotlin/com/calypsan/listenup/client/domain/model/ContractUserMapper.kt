package com.calypsan.listenup.client.domain.model

import com.calypsan.listenup.api.dto.auth.UserRole
import com.calypsan.listenup.api.dto.auth.User as ContractUser

/**
 * Map a contract [ContractUser] (the auth service's identity-only shape)
 * to the client's local [User] domain model.
 *
 * The contract carries identity + role + status + createdAt; the domain
 * model adds nullable profile fields (`firstName`, `lastName`, avatar
 * details, `tagline`) that other features fetch separately. We default
 * those to nulls / sensible UI fallbacks; later profile-fetch paths
 * overwrite them in Room.
 */
fun ContractUser.toDomain(): User =
    User(
        id = id,
        email = email,
        displayName = displayName,
        firstName = null,
        lastName = null,
        isAdmin = role == UserRole.ROOT || role == UserRole.ADMIN,
        avatarType = "auto",
        avatarValue = null,
        avatarColor = "#6B7280",
        tagline = null,
        createdAtMs = createdAt,
        updatedAtMs = createdAt,
    )
