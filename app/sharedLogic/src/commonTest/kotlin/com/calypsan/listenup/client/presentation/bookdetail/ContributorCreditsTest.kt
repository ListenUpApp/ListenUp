package com.calypsan.listenup.client.presentation.bookdetail

import com.calypsan.listenup.client.domain.model.BookContributor
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class ContributorCreditsTest :
    FunSpec({

        fun contributor(
            id: String,
            name: String,
            vararg roles: String,
        ) = BookContributor(id = id, name = name, roles = roles.toList())

        test("groups multiple contributors sharing a role into one row in appearance order") {
            val credits =
                listOf(
                    contributor("1", "George R.R. Martin", "author"),
                    contributor("2", "Roy Dotrice", "narrator"),
                    contributor("3", "Harry Lloyd", "narrator"),
                    contributor("4", "Carice van Houten", "narrator"),
                )

            val groups = groupContributorsByRole(credits)

            groups.size shouldBe 2
            groups[0].roleLabel shouldBe "Author"
            groups[0].contributors.map { it.name } shouldBe listOf("George R.R. Martin")
            groups[1].roleLabel shouldBe "Narrators"
            groups[1].contributors.map { it.name } shouldBe
                listOf("Roy Dotrice", "Harry Lloyd", "Carice van Houten")
        }

        test("a contributor with multiple roles appears once per role") {
            val credits =
                listOf(
                    contributor("1", "Neil Gaiman", "author", "narrator"),
                )

            val groups = groupContributorsByRole(credits)

            groups.map { it.roleLabel } shouldBe listOf("Author", "Narrator")
            groups.all { it.contributors.single().name == "Neil Gaiman" } shouldBe true
        }

        test("a contributor with no roles is grouped under a generic Contributor label") {
            val credits = listOf(contributor("1", "Anonymous"))

            val groups = groupContributorsByRole(credits)

            groups.single().roleLabel shouldBe "Contributor"
            groups
                .single()
                .contributors
                .single()
                .name shouldBe "Anonymous"
        }

        test("empty credits produce no groups") {
            groupContributorsByRole(emptyList()) shouldBe emptyList()
        }

        test("pluralizeRole capitalises a single contributor's role without an s") {
            pluralizeRole("author", 1) shouldBe "Author"
            pluralizeRole("narrator", 1) shouldBe "Narrator"
        }

        test("pluralizeRole appends s to a bare word for multiple contributors") {
            pluralizeRole("author", 2) shouldBe "Authors"
            pluralizeRole("narrator", 3) shouldBe "Narrators"
            pluralizeRole("editor", 4) shouldBe "Editors"
        }

        test("pluralizeRole leaves multi-word and already-plural roles unchanged") {
            pluralizeRole("foreword by", 2) shouldBe "Foreword by"
            pluralizeRole("dramatis personae", 3) shouldBe "Dramatis personae"
            pluralizeRole("chorus", 2) shouldBe "Chorus"
        }
    })
