package com.calypsan.listenup.api.dto.auth

import kotlinx.serialization.Serializable

/**
 * Controls whether and how new users may self-register.
 *
 * - [OPEN]: anyone may register and is immediately active.
 * - [APPROVAL_QUEUE]: registration creates a PENDING_APPROVAL account an admin must approve.
 * - [CLOSED]: self-registration is disabled (invite-only / admin-created accounts).
 */
@Serializable
enum class RegistrationPolicy { OPEN, APPROVAL_QUEUE, CLOSED }
