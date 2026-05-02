package com.calypsan.listenup.api.dto.auth

import kotlinx.serialization.Serializable

/** Admin-issued decision on a pending registration. */
@Serializable
data class PendingRegistrationDecision(
    val userId: UserId,
    val approved: Boolean,
)

/** Result of decidePendingRegistration. Approved branch carries a redemption token. */
@Serializable
sealed interface PendingRegistrationOutcome {
    /**
     * Account activated. The token is one-time and unlocks the next login()
     * for this user without an additional flow.
     */
    @Serializable
    data class Approved(val token: PendingRegistrationToken) : PendingRegistrationOutcome

    /** Account denied. UserStatus moves to DENIED; subsequent login attempts error. */
    @Serializable
    data object Denied : PendingRegistrationOutcome
}
