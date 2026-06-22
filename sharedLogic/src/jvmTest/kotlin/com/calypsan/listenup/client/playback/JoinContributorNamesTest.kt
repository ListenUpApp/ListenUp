package com.calypsan.listenup.client.playback

import com.calypsan.listenup.api.dto.ContributorRole
import com.calypsan.listenup.client.data.local.db.BookContributorCrossRef
import com.calypsan.listenup.client.data.local.db.ContributorEntity
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.ContributorId
import com.calypsan.listenup.core.Timestamp
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * Unit tests for [joinContributorNames] — the pure role-join behind the player's
 * "Narrated by" line (and the author display name). Covers narrator join order,
 * the empty-when-absent contract (the player hides the line), alias attribution,
 * and role filtering.
 */
class JoinContributorNamesTest :
    FunSpec({

        val bookId = BookId("book-jcn-1")

        fun contributor(
            id: String,
            name: String,
        ) = ContributorEntity(
            id = ContributorId(id),
            name = name,
            description = null,
            imagePath = null,
            createdAt = Timestamp(0L),
            updatedAt = Timestamp(0L),
        )

        fun role(
            contributorId: String,
            role: ContributorRole,
            creditedAs: String? = null,
        ) = BookContributorCrossRef(
            bookId = bookId,
            contributorId = ContributorId(contributorId),
            role = role.apiValue,
            creditedAs = creditedAs,
        )

        test("joins multiple narrators, comma-separated, in cross-ref order") {
            val contributors = listOf(contributor("c1", "Roy Dotrice"), contributor("c2", "Kate Reading"))
            val roles = listOf(role("c1", ContributorRole.NARRATOR), role("c2", ContributorRole.NARRATOR))

            joinContributorNames(contributors, roles, ContributorRole.NARRATOR) shouldBe "Roy Dotrice, Kate Reading"
        }

        test("returns empty string when no contributor holds the role") {
            val contributors = listOf(contributor("c1", "George R.R. Martin"))
            val roles = listOf(role("c1", ContributorRole.AUTHOR))

            joinContributorNames(contributors, roles, ContributorRole.NARRATOR) shouldBe ""
        }

        test("prefers creditedAs over the contributor's canonical name") {
            val contributors = listOf(contributor("c1", "Stephen King"))
            val roles = listOf(role("c1", ContributorRole.NARRATOR, creditedAs = "Richard Bachman"))

            joinContributorNames(contributors, roles, ContributorRole.NARRATOR) shouldBe "Richard Bachman"
        }

        test("filters to the requested role, ignoring other roles") {
            val contributors = listOf(contributor("c1", "Author A"), contributor("c2", "Narrator N"))
            val roles = listOf(role("c1", ContributorRole.AUTHOR), role("c2", ContributorRole.NARRATOR))

            joinContributorNames(contributors, roles, ContributorRole.NARRATOR) shouldBe "Narrator N"
        }
    })
