package com.calypsan.listenup.api.dto.auth

import kotlinx.serialization.Serializable

/**
 * Role binding determines authorization. ROOT is the bootstrap admin created via
 * setupRoot and cannot be demoted or deleted (including by themselves).
 */
@Serializable
enum class UserRole { ROOT, ADMIN, MEMBER }

/**
 * Account lifecycle. PENDING_APPROVAL means the user registered against a
 * closed-with-approval-queue instance and is awaiting admin decision.
 */
@Serializable
enum class UserStatus { ACTIVE, PENDING_APPROVAL, DENIED }

/** Why a password fails policy. Surfaces in AuthError.WeakPassword. */
@Serializable
enum class WeakPasswordReason { TOO_SHORT, TOO_LONG, BLANK }
