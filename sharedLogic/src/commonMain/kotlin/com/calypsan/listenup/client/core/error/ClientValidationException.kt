package com.calypsan.listenup.client.core.error

/**
 * A genuine client-side input-validation failure, thrown only at real validation sites so
 * [ErrorMapper] can map it to a user-facing [com.calypsan.listenup.api.error.ValidationError].
 *
 * Distinct from a bare [IllegalArgumentException]: a library `require(...)` or a mapper bug also
 * throws `IllegalArgumentException` with an internal message (e.g. `"Failed requirement."`), and
 * surfacing that verbatim to the user as *their* input problem is dishonest. Only this dedicated
 * type earns the `ValidationError` treatment; everything else folds to a safe `InternalError`.
 *
 * @property userMessage The user-facing, period-terminated validation message.
 * @property field Optional [com.calypsan.listenup.api.error.ValidationError.field] discriminator so
 *   a form can highlight the offending input without substring-matching the message.
 */
class ClientValidationException(
    val userMessage: String,
    val field: String? = null,
) : IllegalArgumentException(userMessage)
