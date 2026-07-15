package com.calypsan.listenup.api.sync

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe

class SyncDomainsSpec :
    FunSpec({
        test("the catalog holds every wire domain exactly once") {
            val names = SyncDomains.all.map { it.name }
            names shouldHaveSize 25
            names.toSet() shouldBe
                setOf(
                    "books",
                    "contributors",
                    "series",
                    "genres",
                    "tags",
                    "book_tags",
                    "moods",
                    "book_moods",
                    "playback_positions",
                    "listening_events",
                    "activities",
                    "user_stats",
                    "libraries",
                    "library_folders",
                    "admin_user_roster",
                    "collections",
                    "collection_books",
                    "collection_shares",
                    "shelves",
                    "shelf_books",
                    "reading_orders",
                    "reading_order_books",
                    "reading_order_follows",
                    "entities",
                    "public_profiles",
                )
        }
    })
