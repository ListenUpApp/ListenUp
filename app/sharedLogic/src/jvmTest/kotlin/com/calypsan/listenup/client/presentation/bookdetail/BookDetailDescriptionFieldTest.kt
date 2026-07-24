package com.calypsan.listenup.client.presentation.bookdetail

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlin.reflect.full.memberProperties

/**
 * Guards against SKIE field shadowing: the iOS Book Detail rendered the whole `Ready` toString because Swift's
 * universal `description` (CustomStringConvertible) shadows a Kotlin `description` field
 * over the SKIE bridge. The fix renamed the field to `descriptionText`. If anyone renames
 * it back to `description`, SKIE will shadow it again — so pin the names here.
 */
class BookDetailDescriptionFieldTest :
    FunSpec({
        val propertyNames = BookDetailUiState.Ready::class.memberProperties.map { it.name }.toSet()

        test("Ready exposes descriptionText for the synopsis") {
            propertyNames.contains("descriptionText") shouldBe true
        }

        test("Ready does NOT expose a property named description (SKIE shadow trap)") {
            propertyNames.contains("description") shouldBe false
        }
    })
