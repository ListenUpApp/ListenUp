package com.calypsan.listenup.konsist

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldNotBeEmpty

/**
 * Konsist guard that pins the deletion of the legacy `client.core.error.AppError` hierarchy: the
 * one and only `AppError` is `com.calypsan.listenup.api.error.AppError`, in `:contract`, shared by
 * server and clients.
 *
 * **This rule used to police the wrong package.** Its filter banned
 * `com.calypsan.listenup.core.error.` — note the missing `.client.` — which is a *different, live*
 * package holding `ErrorBus`. The hierarchy it claims to pin lived in
 * `com.calypsan.listenup.**client**.core.error`, which the filter never inspected. The tell was
 * sitting in its own allowlist: the entry `"com.calypsan.listenup.client.core.error.ErrorMapper"`
 * could never match a filter keyed on `com.calypsan.listenup.core.error.` — unreachable code
 * exempting a package the rule wasn't looking at. Anyone could have recreated
 * `client.core.error.AppError`, imported it everywhere, and the build would have stayed green.
 *
 * It now bans the legacy package and allows the two live infrastructure survivors that still live
 * there ([com.calypsan.listenup.client.core.error.ErrorMapper] — the boundary translator — and
 * `ClientValidationException`). `ErrorBus` sits in `com.calypsan.listenup.core.error` (`:contract`)
 * and is unrelated to the deleted hierarchy, so it is simply out of scope rather than allowlisted.
 */
private const val LEGACY_ERROR_PACKAGE = "com.calypsan.listenup.client.core.error."

/**
 * Infrastructure that survived the hierarchy's deletion and still lives in the legacy package.
 * Everything else under it is either a deleted error type or a re-introduction of one.
 */
private val ALLOWED_SURVIVORS =
    setOf(
        "com.calypsan.listenup.client.core.error.ErrorMapper",
        "com.calypsan.listenup.client.core.error.ClientValidationException",
    )

class NoLegacyAppErrorRule :
    FunSpec({
        test("no production code imports the deleted client.core.error.AppError hierarchy") {
            val files = productionScope().files

            // Vacuity guard: prove the scope resolves at all, so a scope/API change can't leave
            // this passing on an empty file list.
            files.shouldNotBeEmpty()

            val offenders =
                files.flatMap { file ->
                    file.imports
                        .filter { import ->
                            import.name.startsWith(LEGACY_ERROR_PACKAGE) &&
                                ALLOWED_SURVIVORS.none { allowed ->
                                    import.name == allowed || import.name.startsWith("$allowed.")
                                }
                        }.map { "${file.path} -> ${it.name}" }
                }

            offenders.shouldBeEmpty()
        }
    })
