package com.calypsan.listenup.client.navigation

import com.calypsan.listenup.api.metadata.MetadataLocale
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json

class RouteSerializationTest :
    FunSpec({
        val json = Json { encodeDefaults = true }

        test("every Route subtype encodes and decodes to an equal value") {
            val samples: List<Route> = sampleRoutes()
            for (sample in samples) {
                val encoded = json.encodeToString(Route.serializer(), sample)
                val decoded = json.decodeFromString(Route.serializer(), encoded)
                decoded shouldBe sample
            }
        }

        test("every AuthRoute subtype encodes and decodes to an equal value") {
            val samples: List<AuthRoute> = listOf(ServerSelect, ServerSetup, Setup, Login, Register)
            for (sample in samples) {
                val encoded = json.encodeToString(AuthRoute.serializer(), sample)
                val decoded = json.decodeFromString(AuthRoute.serializer(), encoded)
                decoded shouldBe sample
            }
        }

        test("every Route subtype is reachable via sealedSubclasses") {
            val subclasses = Route::class.sealedSubclasses
            // Sanity check — Route has at least the canonical subtypes
            val names = subclasses.map { it.simpleName }.toSet()
            ("Shell" in names) shouldBe true
            ("BookDetail" in names) shouldBe true
        }
    })

/**
 * Construct one sample value per Route subtype. For data class subtypes with
 * required arguments, supply a deterministic test value (e.g., "test-id").
 * Update this list whenever a new Route subtype is added.
 */
private fun sampleRoutes(): List<Route> =
    buildList {
        // Core
        add(Shell)
        add(BookDetail(bookId = "test-book-id"))
        add(BookReaders(bookId = "test-book-id"))
        add(BookEdit(bookId = "test-book-id"))
        add(MatchPreview(bookId = "test-book-id", asin = "test-asin", region = MetadataLocale.DEFAULT))
        add(MetadataSearch(bookId = "test-book-id"))
        add(SeriesDetail(seriesId = "test-series-id"))
        add(
            BrowseFacet(
                kind = com.calypsan.listenup.client.domain.model.FacetKind.Tag,
                facetId = "test-tag-id",
                facetName = "Staff Pick",
            ),
        )
        add(SeriesEdit(seriesId = "test-series-id"))
        add(ContributorDetail(contributorId = "test-contributor-id"))
        add(ContributorBooks(contributorId = "test-contributor-id", role = "author"))
        add(ContributorEdit(contributorId = "test-contributor-id"))
        add(ContributorMetadataSearch(contributorId = "test-contributor-id"))
        add(ContributorMetadataPreview(contributorId = "test-contributor-id", asin = "test-asin"))
        add(InviteRegistration(serverUrl = "https://example.test", inviteCode = "test-code"))

        // Admin
        add(Admin)
        add(CreateInvite)
        add(AdminCollections)
        add(AdminCollectionDetail(collectionId = "test-collection-id"))
        add(AdminInbox)
        add(AdminCategories)
        add(AdminUserDetail(userId = "test-user-id"))
        add(AdminLibrarySettings)

        // Admin Backup
        add(AdminBackups)
        add(CreateBackup)
        add(RestoreBackup(backupId = "test-backup-id"))

        // ABS Import — single linear flow (the legacy list/detail/wizard routes were removed)
        add(ImportFlow)

        // Settings / misc
        add(Settings)
        add(Licenses)
        add(Storage)

        // Shelf
        add(ShelfDetail(shelfId = "test-shelf-id"))
        add(CreateShelf)
        add(ShelfEdit(shelfId = "test-shelf-id"))

        // Library setup
        add(LibrarySetup)

        // Profile
        add(UserProfile(userId = "test-user-id"))
        add(EditProfile)

        // Document viewer (Android-only PDF viewer)
        add(DocumentViewer(localPath = "/data/data/com.example/cache/doc.pdf"))
    }
