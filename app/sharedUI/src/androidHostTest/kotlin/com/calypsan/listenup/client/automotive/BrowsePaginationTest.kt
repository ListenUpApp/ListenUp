package com.calypsan.listenup.client.automotive

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/** Pins the browse pagination slice shared by onGetChildren and onGetSearchResult (#1238). */
class BrowsePaginationTest :
    FunSpec({
        val items = (1..25).toList()

        test("first page") { paginate(items, page = 0, pageSize = 10) shouldBe (1..10).toList() }

        test("middle page") { paginate(items, page = 1, pageSize = 10) shouldBe (11..20).toList() }

        test("last partial page") { paginate(items, page = 2, pageSize = 10) shouldBe (21..25).toList() }

        test("page past the end is empty") { paginate(items, page = 3, pageSize = 10) shouldBe emptyList() }

        test("non-positive pageSize returns everything (head unit did not paginate)") {
            paginate(items, page = 0, pageSize = 0) shouldBe items
        }

        test("Int.MAX_VALUE pageSize does not overflow") {
            paginate(items, page = 0, pageSize = Int.MAX_VALUE) shouldBe items
        }

        test("negative page is treated as first page") {
            paginate(items, page = -1, pageSize = 10) shouldBe (1..10).toList()
        }

        test("isLastPage: mid-sequence page is not last") {
            isLastPage(items, page = 1, pageSize = 10) shouldBe false
        }

        test("isLastPage: final partial page is last") {
            isLastPage(items, page = 2, pageSize = 10) shouldBe true
        }

        test("isLastPage: non-positive pageSize means the whole list was returned") {
            isLastPage(items, page = 0, pageSize = 0) shouldBe true
        }

        test("isLastPage: exact boundary (items.size == (page+1)*pageSize) is last") {
            isLastPage((1..20).toList(), page = 1, pageSize = 10) shouldBe true
        }
    })
