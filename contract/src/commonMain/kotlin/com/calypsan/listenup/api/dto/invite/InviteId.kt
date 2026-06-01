package com.calypsan.listenup.api.dto.invite

import kotlin.jvm.JvmInline
import kotlinx.serialization.Serializable

/** Opaque identifier for an invite. */
@Serializable @JvmInline
value class InviteId(
    val value: String,
)
