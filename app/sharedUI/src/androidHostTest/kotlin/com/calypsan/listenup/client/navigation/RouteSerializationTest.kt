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
            val samples: List<AuthRoute> = sampleAuthRoutes()
            for (sample in samples) {
                val encoded = json.encodeToString(AuthRoute.serializer(), sample)
                val decoded = json.decodeFromString(AuthRoute.serializer(), encoded)
                decoded shouldBe sample
            }
        }

        // A back stack is saved by serializing it, so a route that cannot round-trip is a crash on
        // the first process death with it on the stack. The round-trip above only proves the routes
        // it was given — these two make sure it was given all of them.
        test("every Route subtype has a round-trip sample") {
            sampleRoutes().map { it::class }.toSet() shouldBe Route::class.sealedSubclasses.toSet()
        }

        test("every AuthRoute subtype has a round-trip sample") {
            sampleAuthRoutes().map { it::class }.toSet() shouldBe AuthRoute::class.sealedSubclasses.toSet()
        }
    })

/** One sample value per AuthRoute subtype. */
private fun sampleAuthRoutes(): List<AuthRoute> = listOf(ServerSelect, ServerSetup, Setup, Login, Register, ForgotPassword)

/**
 * One sample value per Route subtype, with deterministic arguments where the subtype takes any.
 * `every Route subtype has a round-trip sample` fails until a new subtype is added here.
 */
internal fun sampleRoutes(): List<Route> =
    buildList {
        // Core
        add(Shell)
        add(BookDetail(bookId = "test-book-id"))
        add(BookReaders(bookId = "test-book-id"))
        add(BookEdit(bookId = "test-book-id"))
        add(MatchPreview(bookId = "test-book-id", asin = "test-asin", region = MetadataLocale.DEFAULT))
        add(MetadataSearch(bookId = "test-book-id"))
        add(ChapterEditor(bookId = "test-book-id"))
        add(BulkEdit(bookIds = listOf("test-book-a", "test-book-b")))
        add(SeriesDetail(seriesId = "test-series-id"))
        add(
            BrowseFacet(
                kind = com.calypsan.listenup.client.domain.model.FacetKind.Tag,
                facetId = "test-tag-id",
                facetName = "Staff Pick",
            ),
        )
        add(GenreDestination(genreId = "test-genre-id"))
        add(SeriesEdit(seriesId = "test-series-id"))
        add(ContributorDetail(contributorId = "test-contributor-id"))
        add(ContributorBooks(contributorId = "test-contributor-id", role = "author"))
        add(ContributorEdit(contributorId = "test-contributor-id"))
        add(ContributorMetadataSearch(contributorId = "test-contributor-id"))
        add(
            ContributorMetadataPreview(
                contributorId = "test-contributor-id",
                asin = "test-asin",
                region = MetadataLocale.DEFAULT,
            ),
        )
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
        add(AdminOrganizeSettings)

        // Admin Backup
        add(AdminBackups)
        add(CreateBackup)
        add(RestoreBackup(backupId = "test-backup-id"))
        add(RestoreFromFile)

        // ABS Import — single linear flow (the legacy list/detail/wizard routes were removed)
        add(ImportFlow)
        add(UploadBooks)

        // Settings / misc
        add(Settings)
        add(Devices)
        add(Notifications)
        add(NotificationSettings)
        add(Licenses)
        add(LicenseDetail(uniqueId = "test-license-id"))
        add(Storage)
        add(HardcoverSettings)

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
