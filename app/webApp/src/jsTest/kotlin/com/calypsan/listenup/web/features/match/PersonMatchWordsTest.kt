package com.calypsan.listenup.web.features.match

import com.calypsan.listenup.api.dto.ContributorRole
import com.calypsan.listenup.api.dto.match.LibraryCredit
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * A role a newer server added decodes as [ContributorRole.UNKNOWN]. The row stays readable: neutral
 * words, never the raw literal and never another role's verb.
 */
class PersonMatchWordsTest :
    FunSpec({
        test("a role this build cannot read is named neutrally") {
            roleLabel(ContributorRole.UNKNOWN) shouldBe "Other role"
        }

        test("a credit in a role this build cannot read still counts, in neutral words") {
            creditsText(listOf(LibraryCredit(ContributorRole.UNKNOWN, bookCount = 2))) shouldBe "Credited on 2 of your books"
        }

        test("a known role keeps its own words") {
            roleLabel(ContributorRole.TRANSLATOR) shouldBe "Translator"
            creditsText(listOf(LibraryCredit(ContributorRole.NARRATOR, bookCount = 3))) shouldBe "Narrated 3 of your books"
        }
    })
