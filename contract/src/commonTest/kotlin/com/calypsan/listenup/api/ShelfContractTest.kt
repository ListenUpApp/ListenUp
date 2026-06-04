package com.calypsan.listenup.api

import com.calypsan.listenup.api.dto.shelf.DiscoveredShelf
import com.calypsan.listenup.api.dto.shelf.Shelf
import com.calypsan.listenup.api.dto.shelf.ShelfBookView
import com.calypsan.listenup.api.dto.shelf.ShelfDetail
import com.calypsan.listenup.api.error.ShelfError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.sync.ShelfBookSyncPayload
import com.calypsan.listenup.api.sync.ShelfSyncPayload
import com.calypsan.listenup.core.ShelfId
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class ShelfContractTest :
    FunSpec({
        test("ShelfSyncPayload round-trips") {
            val p =
                ShelfSyncPayload(
                    id = "s1",
                    name = "To Read",
                    description = "",
                    isPrivate = false,
                    revision = 1,
                    updatedAt = 2,
                    createdAt = 1,
                    deletedAt = null,
                )
            contractJson.decodeFromString<ShelfSyncPayload>(contractJson.encodeToString(p)) shouldBe p
        }

        test("ShelfBookSyncPayload round-trips") {
            val p =
                ShelfBookSyncPayload(
                    id = "s1:b1",
                    shelfId = "s1",
                    bookId = "b1",
                    sortOrder = 0,
                    revision = 1,
                    updatedAt = 2,
                    createdAt = 1,
                    deletedAt = null,
                )
            contractJson.decodeFromString<ShelfBookSyncPayload>(contractJson.encodeToString(p)) shouldBe p
        }

        test("ShelfDetail round-trips") {
            val d =
                ShelfDetail(
                    id = ShelfId("s1"),
                    name = "To Read",
                    description = "",
                    isPrivate = true,
                    isOwner = true,
                    books = listOf(ShelfBookView("b1", "Title", listOf("Author"))),
                    bookCount = 1,
                    totalDurationMs = 1000,
                )
            contractJson.decodeFromString<ShelfDetail>(contractJson.encodeToString(d)) shouldBe d
        }

        test("DiscoveredShelf round-trips") {
            val ds =
                DiscoveredShelf(
                    shelf = Shelf(ShelfId("s1"), "To Read", "", false, 1, 2),
                    ownerId = "u2",
                    ownerDisplayName = "Sam",
                )
            contractJson.decodeFromString<DiscoveredShelf>(contractJson.encodeToString(ds)) shouldBe ds
        }

        test("ShelfError round-trips polymorphically as AppError") {
            val e: AppResult<Unit> = AppResult.Failure(ShelfError.Forbidden())
            contractJson.decodeFromString<AppResult<Unit>>(contractJson.encodeToString(e)) shouldBe e
        }
    })
