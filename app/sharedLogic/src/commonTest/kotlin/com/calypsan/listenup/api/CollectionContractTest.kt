package com.calypsan.listenup.api

import com.calypsan.listenup.api.dto.CollectionShareDto
import com.calypsan.listenup.api.dto.CollectionSummary
import com.calypsan.listenup.api.dto.CreateCollectionBody
import com.calypsan.listenup.api.dto.ShareCollectionBody
import com.calypsan.listenup.api.dto.SharePermission
import com.calypsan.listenup.api.dto.auth.UserId
import com.calypsan.listenup.api.sync.CollectionBookSyncPayload
import com.calypsan.listenup.api.sync.CollectionShareSyncPayload
import com.calypsan.listenup.api.sync.CollectionSyncPayload
import com.calypsan.listenup.core.CollectionId
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class CollectionContractTest :
    FunSpec({
        val json = Json { encodeDefaults = true }

        // ── SharePermission ───────────────────────────────────────────────────

        test("SharePermission.Read round-trips") {
            val encoded = json.encodeToString(SharePermission.Read)
            json.decodeFromString<SharePermission>(encoded) shouldBe SharePermission.Read
        }

        test("SharePermission.Write round-trips") {
            val encoded = json.encodeToString(SharePermission.Write)
            json.decodeFromString<SharePermission>(encoded) shouldBe SharePermission.Write
        }

        test("SharePermission.Write.canWrite() is true") {
            SharePermission.Write.canWrite() shouldBe true
        }

        test("SharePermission.Read.canWrite() is false") {
            SharePermission.Read.canWrite() shouldBe false
        }

        test("SharePermission.Read.canRead() is true") {
            SharePermission.Read.canRead() shouldBe true
        }

        test("SharePermission.Write.canRead() is true") {
            SharePermission.Write.canRead() shouldBe true
        }

        // ── CollectionSyncPayload ─────────────────────────────────────────────

        test("CollectionSyncPayload round-trips with all fields") {
            val original =
                CollectionSyncPayload(
                    id = "col-1",
                    libraryId = "lib-1",
                    ownerId = "user-1",
                    name = "My Favourites",
                    isInbox = false,
                    revision = 10L,
                    updatedAt = 1730000000000L,
                    deletedAt = null,
                )
            val decoded = json.decodeFromString<CollectionSyncPayload>(json.encodeToString(original))
            decoded shouldBe original
        }

        test("CollectionSyncPayload round-trips as tombstone") {
            val original =
                CollectionSyncPayload(
                    id = "col-2",
                    libraryId = "lib-1",
                    ownerId = "user-1",
                    name = "Deleted",
                    isInbox = false,
                    revision = 20L,
                    updatedAt = 1730000000000L,
                    deletedAt = 1730000005000L,
                )
            val decoded = json.decodeFromString<CollectionSyncPayload>(json.encodeToString(original))
            decoded shouldBe original
        }

        test("CollectionSyncPayload round-trips with isInbox=true") {
            val original =
                CollectionSyncPayload(
                    id = "col-inbox",
                    libraryId = "lib-1",
                    ownerId = "user-1",
                    name = "Inbox",
                    isInbox = true,
                    revision = 1L,
                    updatedAt = 1730000000000L,
                )
            val decoded = json.decodeFromString<CollectionSyncPayload>(json.encodeToString(original))
            decoded shouldBe original
        }

        // ── CollectionBookSyncPayload ─────────────────────────────────────────

        test("CollectionBookSyncPayload round-trips with all fields") {
            val original =
                CollectionBookSyncPayload(
                    id = "a1b2c3d4e5f60718293a4b5c6d7e8f90",
                    collectionId = "col-1",
                    bookId = "book-1",
                    createdAt = 1730000000000L,
                    revision = 5L,
                    deletedAt = null,
                )
            val decoded =
                json.decodeFromString<CollectionBookSyncPayload>(json.encodeToString(original))
            decoded shouldBe original
            decoded.id shouldBe "a1b2c3d4e5f60718293a4b5c6d7e8f90"
        }

        test("CollectionBookSyncPayload round-trips as tombstone") {
            val original =
                CollectionBookSyncPayload(
                    id = "b2c3d4e5f60718293a4b5c6d7e8f9001",
                    collectionId = "col-1",
                    bookId = "book-2",
                    createdAt = 1730000000000L,
                    revision = 8L,
                    deletedAt = 1730000010000L,
                )
            val decoded =
                json.decodeFromString<CollectionBookSyncPayload>(json.encodeToString(original))
            decoded shouldBe original
            decoded.id shouldBe "b2c3d4e5f60718293a4b5c6d7e8f9001"
        }

        // ── CollectionShareSyncPayload ────────────────────────────────────────

        test("CollectionShareSyncPayload round-trips with SharePermission.Read") {
            val original =
                CollectionShareSyncPayload(
                    id = "share-1",
                    collectionId = "col-1",
                    sharedWithUserId = "user-2",
                    sharedByUserId = "user-1",
                    permission = SharePermission.Read,
                    revision = 3L,
                    updatedAt = 1730000000000L,
                    deletedAt = null,
                )
            val decoded =
                json.decodeFromString<CollectionShareSyncPayload>(json.encodeToString(original))
            decoded shouldBe original
        }

        test("CollectionShareSyncPayload round-trips with SharePermission.Write") {
            val original =
                CollectionShareSyncPayload(
                    id = "share-2",
                    collectionId = "col-1",
                    sharedWithUserId = "user-3",
                    sharedByUserId = "user-1",
                    permission = SharePermission.Write,
                    revision = 4L,
                    updatedAt = 1730000000000L,
                    deletedAt = null,
                )
            val decoded =
                json.decodeFromString<CollectionShareSyncPayload>(json.encodeToString(original))
            decoded shouldBe original
        }

        test("CollectionShareSyncPayload round-trips as tombstone") {
            val original =
                CollectionShareSyncPayload(
                    id = "share-3",
                    collectionId = "col-1",
                    sharedWithUserId = "user-4",
                    sharedByUserId = "user-1",
                    permission = SharePermission.Read,
                    revision = 6L,
                    updatedAt = 1730000000000L,
                    deletedAt = 1730000020000L,
                )
            val decoded =
                json.decodeFromString<CollectionShareSyncPayload>(json.encodeToString(original))
            decoded shouldBe original
        }

        // ── CollectionSummary ─────────────────────────────────────────────────

        test("CollectionSummary round-trips with isSystem true") {
            val original =
                CollectionSummary(
                    id = CollectionId("col-sys"),
                    name = "All Books",
                    ownerId = UserId("system"),
                    isInbox = false,
                    isSystem = true,
                    bookCount = 3L,
                    callerPermission = SharePermission.Write,
                    isOwner = true,
                )
            val decoded = json.decodeFromString<CollectionSummary>(json.encodeToString(original))
            decoded shouldBe original
        }

        test("CollectionSyncPayload round-trips with isSystem true") {
            val original =
                CollectionSyncPayload(
                    id = "col-sys",
                    libraryId = "lib-1",
                    ownerId = "system",
                    name = "All Books",
                    isInbox = false,
                    isSystem = true,
                    revision = 1L,
                    updatedAt = 1730000000000L,
                    deletedAt = null,
                )
            val decoded = json.decodeFromString<CollectionSyncPayload>(json.encodeToString(original))
            decoded shouldBe original
        }

        test("CollectionSummary round-trips as owner") {
            val original =
                CollectionSummary(
                    id = CollectionId("col-1"),
                    name = "My Favourites",
                    ownerId = UserId("user-1"),
                    isInbox = false,
                    bookCount = 42L,
                    callerPermission = SharePermission.Write,
                    isOwner = true,
                )
            val decoded = json.decodeFromString<CollectionSummary>(json.encodeToString(original))
            decoded shouldBe original
        }

        test("CollectionSummary round-trips as read-only share recipient") {
            val original =
                CollectionSummary(
                    id = CollectionId("col-2"),
                    name = "Shared With Me",
                    ownerId = UserId("user-2"),
                    isInbox = false,
                    bookCount = 7L,
                    callerPermission = SharePermission.Read,
                    isOwner = false,
                )
            val decoded = json.decodeFromString<CollectionSummary>(json.encodeToString(original))
            decoded shouldBe original
        }

        // ── CollectionShareDto ────────────────────────────────────────────────

        test("CollectionShareDto round-trips") {
            val original =
                CollectionShareDto(
                    id = "share-1",
                    collectionId = CollectionId("col-1"),
                    sharedWithUserId = UserId("user-2"),
                    permission = SharePermission.Write,
                )
            val decoded = json.decodeFromString<CollectionShareDto>(json.encodeToString(original))
            decoded shouldBe original
        }

        // ── CreateCollectionBody ──────────────────────────────────────────────

        test("CreateCollectionBody round-trips") {
            val original = CreateCollectionBody(libraryId = "lib-1", name = "My Audiobooks")
            val decoded =
                json.decodeFromString<CreateCollectionBody>(json.encodeToString(original))
            decoded shouldBe original
        }

        // ── ShareCollectionBody ───────────────────────────────────────────────

        test("ShareCollectionBody round-trips with default permission") {
            val original = ShareCollectionBody(sharedWithUserId = "user-2")
            val decoded =
                json.decodeFromString<ShareCollectionBody>(json.encodeToString(original))
            decoded shouldBe original
            decoded.permission shouldBe SharePermission.Read
        }

        test("ShareCollectionBody round-trips with Write permission") {
            val original =
                ShareCollectionBody(
                    sharedWithUserId = "user-3",
                    permission = SharePermission.Write,
                )
            val decoded =
                json.decodeFromString<ShareCollectionBody>(json.encodeToString(original))
            decoded shouldBe original
        }
    })
