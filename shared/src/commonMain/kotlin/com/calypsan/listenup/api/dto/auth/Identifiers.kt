package com.calypsan.listenup.api.dto.auth

import kotlinx.serialization.Serializable
import kotlin.jvm.JvmInline

/** Stable identifier for a user. UUIDv7 string at the storage layer. */
@Serializable @JvmInline
value class UserId(
    val value: String,
)

/** Stable identifier for a session row. Surfaces in JWT `jti` claim. */
@Serializable @JvmInline
value class SessionId(
    val value: String,
)

/** Short-lived JWT bearer token (HS256). Opaque to the client; never decoded. */
@Serializable @JvmInline
value class AccessToken(
    val value: String,
)

/** Long-lived opaque refresh secret (32 random bytes, base64url). Never a JWT. */
@Serializable @JvmInline
value class RefreshToken(
    val value: String,
)
