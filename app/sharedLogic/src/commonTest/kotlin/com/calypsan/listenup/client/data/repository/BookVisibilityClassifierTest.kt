package com.calypsan.listenup.client.data.repository

import com.calypsan.listenup.client.data.local.db.AdminUserRosterEntity
import com.calypsan.listenup.client.data.local.db.CollectionEntity
import com.calypsan.listenup.client.data.local.db.CollectionShareEntity
import com.calypsan.listenup.client.domain.model.BookVisibility
import com.calypsan.listenup.client.domain.model.CollectionRef
import com.calypsan.listenup.client.domain.model.HiddenFrom
import com.calypsan.listenup.client.test.ALL_BOOKS_ID
import com.calypsan.listenup.client.test.allBooksCollection
import com.calypsan.listenup.client.test.collectionShare
import com.calypsan.listenup.client.test.inboxCollection
import com.calypsan.listenup.client.test.normalCollection
import com.calypsan.listenup.client.test.rosterUser
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * [classifyBookVisibility] — every state and edge from the collection-visibility spec, §5.
 * Pure: no Room, no flows.
 */
class BookVisibilityClassifierTest :
    FunSpec({
        val roster =
            listOf(
                rosterUser("root", "Root", role = "ROOT"),
                rosterUser("admin", "Ada Admin", role = "ADMIN"),
                rosterUser("alice", "Alice"),
                rosterUser("bob", "bob"),
                rosterUser("carol", "Carol"),
            )
        val kids = normalCollection("c1", "Kids")
        val adults = normalCollection("c2", "adults")

        fun classify(
            holding: List<CollectionEntity>,
            shares: List<CollectionShareEntity> = emptyList(),
            users: List<AdminUserRosterEntity> = roster,
            isHeld: Boolean = false,
        ) = classifyBookVisibility(isHeld, holding, shares, users)

        test("a book only in All Books is Public") {
            classify(listOf(allBooksCollection())) shouldBe BookVisibility.Public
        }

        test("a book in no live collection is Stranded") {
            classify(emptyList()) shouldBe BookVisibility.Stranded
        }

        test("a held book is Held") {
            classify(listOf(inboxCollection()), isHeld = true) shouldBe BookVisibility.Held
        }

        test("a held book also curated into a collection is still Held — the inbox's held set decides") {
            classify(listOf(inboxCollection(), kids), isHeld = true) shouldBe BookVisibility.Held
        }

        test("held is the input, not the rows: an inbox row the held set does not count is ignored") {
            classify(listOf(inboxCollection(), allBooksCollection())) shouldBe BookVisibility.Public
        }

        test("a book in one collection shared with no one is hidden from every member") {
            classify(listOf(kids)) shouldBe
                BookVisibility.Restricted(listOf(CollectionRef("c1", "Kids")), HiddenFrom.Everyone)
        }

        test("a book in a normal collection AND All Books is Restricted — normal membership decides") {
            classify(listOf(allBooksCollection(), kids)) shouldBe
                BookVisibility.Restricted(listOf(CollectionRef("c1", "Kids")), HiddenFrom.Everyone)
        }

        test("names the members who are neither owner nor shared in, sorted case-insensitively") {
            classify(listOf(kids), shares = listOf(collectionShare("c1", "carol"))) shouldBe
                BookVisibility.Restricted(
                    listOf(CollectionRef("c1", "Kids")),
                    HiddenFrom.Members(listOf("Alice", "bob")),
                )
        }

        test("names are sorted case-insensitively whatever order the roster arrives in") {
            val outOfOrder = listOf(rosterUser("zed", "zed"), rosterUser("amy", "Amy"), rosterUser("ben", "ben"))
            classify(listOf(kids), users = outOfOrder) shouldBe
                BookVisibility.Restricted(listOf(CollectionRef("c1", "Kids")), HiddenFrom.Everyone)
            classify(listOf(kids), shares = listOf(collectionShare("c1", "amy")), users = outOfOrder) shouldBe
                BookVisibility.Restricted(listOf(CollectionRef("c1", "Kids")), HiddenFrom.Members(listOf("ben", "zed")))
        }

        test("two hidden members with the same name are told apart by their email") {
            val users =
                listOf(
                    rosterUser("alex2", "Alex", email = "alex@b.com"),
                    rosterUser("ben", "Ben"),
                    rosterUser("alex1", "Alex", email = "alex@a.com"),
                    rosterUser("carol", "Carol"),
                )
            classify(listOf(kids), shares = listOf(collectionShare("c1", "carol")), users = users) shouldBe
                BookVisibility.Restricted(
                    listOf(CollectionRef("c1", "Kids")),
                    HiddenFrom.Members(listOf("Alex (alex@a.com)", "Alex (alex@b.com)", "Ben")),
                )
        }

        test("a name shared only with a member who can see the book needs no email") {
            val users = listOf(rosterUser("alex1", "Alex"), rosterUser("alex2", "Alex"), rosterUser("ben", "Ben"))
            classify(listOf(kids), shares = listOf(collectionShare("c1", "alex2")), users = users) shouldBe
                BookVisibility.Restricted(listOf(CollectionRef("c1", "Kids")), HiddenFrom.Members(listOf("Alex", "Ben")))
        }

        test("multiple collections are listed by name, and a share in any one of them is enough") {
            classify(
                holding = listOf(kids, adults),
                shares = listOf(collectionShare("c1", "alice"), collectionShare("c2", "bob")),
            ) shouldBe
                BookVisibility.Restricted(
                    listOf(CollectionRef("c2", "adults"), CollectionRef("c1", "Kids")),
                    HiddenFrom.Members(listOf("Carol")),
                )
        }

        test("owning one of the book's collections is enough — a member's legacy collection counts") {
            val alicesOwn = normalCollection("c3", "Alice's", ownerId = "alice")
            classify(
                holding = listOf(alicesOwn),
                shares = listOf(collectionShare("c3", "bob"), collectionShare("c3", "carol")),
            ) shouldBe BookVisibility.Restricted(listOf(CollectionRef("c3", "Alice's")), HiddenFrom.Nobody)
        }

        test("hidden from nobody when every member is shared in") {
            classify(
                holding = listOf(kids),
                shares = listOf("alice", "bob", "carol").map { collectionShare("c1", it) },
            ) shouldBe BookVisibility.Restricted(listOf(CollectionRef("c1", "Kids")), HiddenFrom.Nobody)
        }

        test("admins are never named in hidden from") {
            classify(
                holding = listOf(kids),
                shares = listOf(collectionShare("c1", "alice"), collectionShare("c1", "carol")),
            ) shouldBe BookVisibility.Restricted(listOf(CollectionRef("c1", "Kids")), HiddenFrom.Members(listOf("bob")))
        }

        test("pending and tombstoned roster rows are not members") {
            val users =
                roster +
                    rosterUser("pat", "Pat", status = "PENDING_APPROVAL") +
                    rosterUser("tom", "Tom", deletedAt = 5L)
            classify(
                holding = listOf(kids),
                shares = listOf(collectionShare("c1", "alice")),
                users = users,
            ) shouldBe
                BookVisibility.Restricted(listOf(CollectionRef("c1", "Kids")), HiddenFrom.Members(listOf("bob", "Carol")))
        }

        test("a blank display name falls back to the email") {
            val users = listOf(rosterUser("dee", "  ", email = "dee@example.com"), rosterUser("alice", "Alice"))
            classify(holding = listOf(kids), shares = listOf(collectionShare("c1", "alice")), users = users) shouldBe
                BookVisibility.Restricted(
                    listOf(CollectionRef("c1", "Kids")),
                    HiddenFrom.Members(listOf("dee@example.com")),
                )
        }

        test("a tombstoned collection is ignored") {
            classify(listOf(normalCollection("c1", "Kids", deletedAt = 5L))) shouldBe BookVisibility.Stranded
        }

        test("a revoked share is ignored") {
            classify(
                holding = listOf(kids),
                shares =
                    listOf(
                        collectionShare("c1", "alice", deletedAt = 5L),
                        collectionShare("c1", "bob"),
                        collectionShare("c1", "carol"),
                    ),
            ) shouldBe BookVisibility.Restricted(listOf(CollectionRef("c1", "Kids")), HiddenFrom.Members(listOf("Alice")))
        }

        test("a share of a collection the book is not in grants nothing") {
            classify(
                holding = listOf(kids),
                shares = listOf(collectionShare("c2", "alice"), collectionShare(ALL_BOOKS_ID, "bob")),
            ) shouldBe BookVisibility.Restricted(listOf(CollectionRef("c1", "Kids")), HiddenFrom.Everyone)
        }

        test("a server with no members reports hidden from nobody") {
            classify(listOf(kids), users = listOf(rosterUser("root", "Root", role = "ROOT"))) shouldBe
                BookVisibility.Restricted(listOf(CollectionRef("c1", "Kids")), HiddenFrom.Nobody)
        }
    })
