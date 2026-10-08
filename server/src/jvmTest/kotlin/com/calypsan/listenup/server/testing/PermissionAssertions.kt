package com.calypsan.listenup.server.testing

import com.calypsan.listenup.api.error.AuthError
import com.calypsan.listenup.api.result.AppResult
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.matchers.types.shouldNotBeInstanceOf

/** The call was refused at the permission gate. */
fun AppResult<*>.shouldBeDeniedPermission() {
    shouldBeInstanceOf<AppResult.Failure>().error.shouldBeInstanceOf<AuthError.PermissionDenied>()
}

/** The call got past the permission gate: it succeeded, or failed for some other reason. */
fun AppResult<*>.shouldPassThePermissionGate() {
    if (this is AppResult.Failure) error.shouldNotBeInstanceOf<AuthError.PermissionDenied>()
}
