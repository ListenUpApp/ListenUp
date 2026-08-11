package com.calypsan.listenup.client.domain.model

import com.calypsan.listenup.api.dto.auth.UserRole
import com.calypsan.listenup.api.dto.auth.User as ContractUser

/**
 * Map a contract [ContractUser] (the auth service's identity-only shape)
 * to the client's local [User] domain model.
 *
 * The contract carries identity + role + status + permissions + createdAt; the domain
 * model adds nullable profile fields (`firstName`, `lastName`, `tagline`)
 * that other features fetch separately. We default those to null; later
 * profile-fetch paths overwrite them in Room. Avatar state is not part of
 * [User] — it lives in the synced `public_profiles` row.
 *
 * [User.permissions] is carried across verbatim. It used not to be (#1270): this mapper reduced
 * the contract user to [User.isAdmin] alone, so `canEdit` and `canShare` never reached the domain
 * model and no client could tell a member what they were allowed to do — while the server went on
 * enforcing both on every metadata mutation. Nothing looked broken from the outside, which is
 * exactly why it survived so long.
 */
fun ContractUser.toDomain(): User =
    User(
        id = id,
        email = email,
        displayName = displayName,
        firstName = null,
        lastName = null,
        isAdmin = role == UserRole.ROOT || role == UserRole.ADMIN,
        permissions =
            UserPermissions(
                canEdit = permissions.canEdit,
                canShare = permissions.canShare,
            ),
        tagline = null,
        createdAtMs = createdAt,
        updatedAtMs = createdAt,
    )
