package com.calypsan.listenup.client.presentation.bookdetail

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlin.reflect.full.memberProperties

/**
 * Guards the synopsis field's name. Every bridged object already carries `NSObject.description`
 * on the Swift side, and Swift Export does not export a Kotlin property called `description`
 * under that name, so a Swift `ready.description` renders the object's own description instead
 * of the synopsis. The field is `descriptionText`; these tests pin that name.
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
