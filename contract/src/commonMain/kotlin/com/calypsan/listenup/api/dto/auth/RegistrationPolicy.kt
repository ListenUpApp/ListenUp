package com.calypsan.listenup.api.dto.auth

import com.calypsan.listenup.api.LenientEnumSerializer
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable

/**
 * Controls whether and how new users may self-register.
 *
 * - [OPEN]: anyone may register and is immediately active.
 * - [APPROVAL_QUEUE]: registration creates a PENDING_APPROVAL account an admin must approve.
 * - [CLOSED]: self-registration is disabled (invite-only / admin-created accounts).
 *
 * Serialized by [RegistrationPolicySerializer]: the policy is also a bare RPC result
 * (`getRegistrationPolicy`, `observeRegistrationPolicy`), where no property default can catch a
 * policy a newer server added. Such a policy reads as [CLOSED], the same fallback
 * [com.calypsan.listenup.api.dto.ServerInfo.registrationPolicy] declares: the app hides
 * self-registration and the server still enforces the real policy.
 */
@Serializable(with = RegistrationPolicySerializer::class)
enum class RegistrationPolicy { OPEN, APPROVAL_QUEUE, CLOSED }

/** Decodes [RegistrationPolicy] by name, falling back to [RegistrationPolicy.CLOSED] for one this build has never heard of. */
object RegistrationPolicySerializer :
    KSerializer<RegistrationPolicy> by LenientEnumSerializer(
        "RegistrationPolicy",
        RegistrationPolicy.entries,
        RegistrationPolicy.CLOSED,
    )
