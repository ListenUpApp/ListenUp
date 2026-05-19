package com.calypsan.listenup.api.dto.auth

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Admin-issued decision on a pending registration. */
@Serializable
data class PendingRegistrationDecision(
    @SerialName("userId")
    val userId: UserId,
    val approved: Boolean,
)

/**
 * Result of decidePendingRegistration. Approval is a state change on the user
 * row, not a token transaction — the applicant simply retries `login()` and it
 * succeeds once status flips ACTIVE. No redemption token, no out-of-band ceremony.
 *
 * Notification of the applicant (email, push, polling) is a separate concern
 * outside the auth contract.
 */
@Serializable
sealed interface PendingRegistrationOutcome {
    /** Account activated. UserStatus moves to ACTIVE; the applicant's next login() succeeds. */
    @Serializable
    data object Approved : PendingRegistrationOutcome

    /** Account denied. UserStatus moves to DENIED; subsequent login attempts error. */
    @Serializable
    data object Denied : PendingRegistrationOutcome
}
