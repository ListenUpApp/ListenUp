package com.calypsan.listenup.client.core

/**
 * Canonical discriminators for [com.calypsan.listenup.api.error.ValidationError.field].
 *
 * The auth/admin use cases stamp one of these onto a client-side [com.calypsan.listenup.api.error.ValidationError]
 * so a form ViewModel can highlight the offending input by *field identity* rather than by
 * substring-matching the user-facing message. Kept `internal`: producers (use cases) and
 * consumers (ViewModels) both live in this module, so it never needs to cross the export surface.
 */
internal object ValidationField {
    const val FIRST_NAME = "firstName"
    const val LAST_NAME = "lastName"
    const val EMAIL = "email"
    const val PASSWORD = "password"
}
