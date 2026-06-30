package com.calypsan.listenup.konsist

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty

/**
 * Pins the compiler-enforced `AppResult` must-use invariant.
 *
 * Every `domain/repository/` interface file that declares an `AppResult`-returning function
 * carries `@file:MustUseReturnValues`, so the unused-return-value checker
 * (`-Xreturn-value-checker=check`, promoted to an error via
 * `-Xwarning-level=RETURN_VALUE_NOT_USED:error`) makes an ignored `AppResult` — a swallowed
 * error — a build failure at every call site. Dropping the annotation from one of these files
 * would silently disarm the check for that repository, so make the omission a build failure.
 *
 * The `: AppResult<` / `@file:MustUseReturnValues` text checks mirror the arming criterion
 * exactly (the same `grep` that selected the files to annotate), matching the string-level
 * style of the sibling Konsist rules.
 */
class AppResultSurfaceIsMustUseRule :
    FunSpec({
        test("every domain/repository interface returning AppResult is @file:MustUseReturnValues") {
            val offenders =
                productionScope()
                    .files
                    .filter { "/sharedLogic/" in it.path && "/domain/repository/" in it.path }
                    .filter { it.text.contains(": AppResult<") }
                    .filter { !it.text.contains("@file:MustUseReturnValues") }
                    .map { it.path }

            offenders.shouldBeEmpty()
        }
    })
