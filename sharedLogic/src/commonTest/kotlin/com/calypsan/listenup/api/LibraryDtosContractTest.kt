package com.calypsan.listenup.api

import com.calypsan.listenup.api.dto.AccessMode
import com.calypsan.listenup.api.dto.DirectoryEntry
import com.calypsan.listenup.api.dto.Library
import com.calypsan.listenup.api.dto.LibraryFolder
import com.calypsan.listenup.api.dto.LibraryFolderRef
import com.calypsan.listenup.api.dto.SetupStatus
import com.calypsan.listenup.api.dto.auth.UserId
import com.calypsan.listenup.core.FolderId
import com.calypsan.listenup.core.LibraryId
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.serialization.encodeToString

/**
 * Round-trip every Library DTO through [contractJson].
 *
 * Any drift in field names, polymorphic discriminators, or default-value
 * handling fails here before any pipeline code runs.
 */
class LibraryDtosContractTest :
    FunSpec({

        // ── Library ───────────────────────────────────────────────────────────

        test("Library round-trips with all fields populated") {
            val library =
                Library(
                    id = LibraryId("lib-001"),
                    name = "My Audiobooks",
                    folders =
                        listOf(
                            LibraryFolderRef(
                                id = FolderId("folder-001"),
                                rootPath = "/data/audiobooks",
                            ),
                            LibraryFolderRef(
                                id = FolderId("folder-002"),
                                rootPath = "/media/books",
                            ),
                        ),
                    metadataPrecedence = "embedded,abs,sidecar",
                    accessMode = AccessMode.SHARED,
                    createdByUserId = UserId("user-001"),
                    createdAt = 1_730_000_000_000L,
                )
            roundTrip<Library>(library) shouldBe library
        }

        test("Library round-trips with null createdByUserId") {
            val library =
                Library(
                    id = LibraryId("lib-002"),
                    name = "Demo Library",
                    folders = emptyList(),
                    metadataPrecedence = "embedded,abs,sidecar",
                    accessMode = AccessMode.PRIVATE,
                    createdByUserId = null,
                    createdAt = 1_729_000_000_000L,
                )
            roundTrip<Library>(library) shouldBe library
        }

        // ── LibraryFolder ─────────────────────────────────────────────────────

        test("LibraryFolder round-trips") {
            val folder =
                LibraryFolder(
                    id = FolderId("folder-003"),
                    libraryId = LibraryId("lib-001"),
                    rootPath = "/mnt/nas/audiobooks",
                    createdAt = 1_730_000_500_000L,
                )
            roundTrip<LibraryFolder>(folder) shouldBe folder
        }

        // ── LibraryFolderRef ──────────────────────────────────────────────────

        test("LibraryFolderRef round-trips") {
            val ref =
                LibraryFolderRef(
                    id = FolderId("folder-004"),
                    rootPath = "/audio/stormlight",
                )
            roundTrip<LibraryFolderRef>(ref) shouldBe ref
        }

        // ── SetupStatus ───────────────────────────────────────────────────────

        test("SetupStatus round-trips with needsSetup = true") {
            val status = SetupStatus(needsSetup = true)
            roundTrip<SetupStatus>(status) shouldBe status
        }

        test("SetupStatus round-trips with needsSetup = false") {
            val status = SetupStatus(needsSetup = false)
            roundTrip<SetupStatus>(status) shouldBe status
        }

        test("SetupStatus round-trips with isScanning = true") {
            val status = SetupStatus(needsSetup = false, isScanning = true)
            roundTrip<SetupStatus>(status) shouldBe status
        }

        // ── DirectoryEntry ────────────────────────────────────────────────────

        test("DirectoryEntry round-trips") {
            val entry =
                DirectoryEntry(
                    name = "audiobooks",
                    path = "/data/audiobooks",
                    hasChildren = true,
                    itemCount = 7,
                )
            roundTrip<DirectoryEntry>(entry) shouldBe entry
        }

        test("DirectoryEntry round-trips with hasChildren = false") {
            val entry =
                DirectoryEntry(
                    name = "empty-folder",
                    path = "/data/empty-folder",
                    hasChildren = false,
                    itemCount = 0,
                )
            roundTrip<DirectoryEntry>(entry) shouldBe entry
        }

        // ── AccessMode ────────────────────────────────────────────────────────

        test("AccessMode enum round-trips all values") {
            AccessMode.entries.forEach { mode ->
                roundTrip<AccessMode>(mode) shouldBe mode
            }
        }

        test("AccessMode wire values match spec") {
            contractJson.encodeToString(AccessMode.SHARED) shouldBe "\"SHARED\""
            contractJson.encodeToString(AccessMode.PRIVATE) shouldBe "\"PRIVATE\""
            contractJson.encodeToString(AccessMode.RESTRICTED) shouldBe "\"RESTRICTED\""
        }
    })

private inline fun <reified T : Any> roundTrip(value: T): T = contractJson.decodeFromString<T>(contractJson.encodeToString(value))
