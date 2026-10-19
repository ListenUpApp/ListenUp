import SwiftUI
import Shared

// MARK: - Navigation Destinations

/// The shell's value-typed navigation destinations, beside `MainTabView` so the view's own file stays within its length
/// budget.
extension View {
    /// All value-typed navigation destinations for the app shell, split into book, content, shelf, social and admin
    /// groups so no builder trips the function-length lint.
    func navigationDestinations() -> some View {
        bookDestinations()
            .contentDestinations()
            .shelfDestinations()
            .socialDestinations()
            .adminDestinations()
    }

    /// A book, and everyone reading it.
    func bookDestinations() -> some View {
        self
            .navigationDestination(for: BookDestination.self) { destination in
                BookDetailView(bookId: destination.id, focus: destination.focus)
                    .heroDestination(bookCoverHeroID(destination.id))
            }
            .navigationDestination(for: BookReadersDestination.self) { destination in
                BookReadersListView(bookId: destination.bookId)
            }
    }

    /// Library/content-facing destinations: series, contributors, facets, settings, and storage.
    func contentDestinations() -> some View {
        self
            .navigationDestination(for: SeriesDestination.self) { destination in
                SeriesDetailView(seriesId: destination.id)
                    .heroDestination(seriesHeroID(destination.id))
            }
            .navigationDestination(for: ContributorDestination.self) { destination in
                ContributorDetailView(contributorId: destination.id)
                    .heroDestination(contributorHeroID(destination.id))
            }
            .navigationDestination(for: ContributorBooksDestination.self) { destination in
                ContributorBooksView(
                    contributorId: destination.contributorId,
                    role: destination.role,
                    contributorName: destination.contributorName,
                    roleDisplayName: destination.roleDisplayName
                )
            }
            .navigationDestination(for: FacetDestination.self) { destination in
                FacetBooksView(kind: destination.kind, facetId: destination.id, facetName: destination.name)
            }
            .navigationDestination(for: GenreDestination.self) { destination in
                GenrePageView(genreId: destination.genreId, genreName: destination.genreName)
            }
            .navigationDestination(for: SettingsDestination.self) { _ in
                SettingsView()
            }
            .navigationDestination(for: StorageDestination.self) { _ in
                StorageView()
            }
            .navigationDestination(for: DevicesDestination.self) { _ in
                DevicesView()
            }
            .navigationDestination(for: NotificationPrefsDestination.self) { _ in
                NotificationPrefsView()
            }
            .navigationDestination(for: HardcoverDestination.self) { _ in
                HardcoverSettingsView()
            }
            .navigationDestination(for: HardcoverKeptOffDestination.self) { _ in
                HardcoverKeptOffView()
            }
            .navigationDestination(for: LicensesDestination.self) { _ in
                LicensesView()
            }
            .navigationDestination(for: LicenseDetailDestination.self) { destination in
                LicenseDetailView(packageName: destination.packageName)
            }
    }

    /// The people-facing destinations: profiles and See All Activity.
    func socialDestinations() -> some View {
        self
            .navigationDestination(for: ActivityFeedDestination.self) { _ in
                ActivityFeedView()
            }
            .navigationDestination(for: UserProfileDestination.self) { _ in
                UserProfileView()
            }
            .navigationDestination(for: ProfileDestination.self) { destination in
                ProfileDestinationView(userId: destination.userId)
            }
    }

    /// Shelf destinations: one shelf, and the reader's own list of them (spec §3.2 H24).
    func shelfDestinations() -> some View {
        self
            .navigationDestination(for: ShelfDestination.self) { destination in
                ShelfDetailView(shelfId: destination.id)
            }
            .navigationDestination(for: MyShelvesDestination.self) { _ in
                MyShelvesView()
            }
    }

    /// Administration destinations (admin / root only surfaces).
    func adminDestinations() -> some View {
        self
            .navigationDestination(for: AdminDestination.self) { destination in
                AdminView(focus: destination.focus)
            }
            .navigationDestination(for: AdminInboxDestination.self) { _ in
                AdminInboxView()
            }
            .navigationDestination(for: ABSImportDestination.self) { _ in
                ABSImportHubView()
            }
            .navigationDestination(for: AdminCollectionsDestination.self) { _ in
                AdminCollectionsView()
            }
            .navigationDestination(for: AdminCategoriesDestination.self) { _ in
                AdminCategoriesView()
            }
            .navigationDestination(for: AdminCollectionDetailDestination.self) { destination in
                AdminCollectionDetailView(collectionId: destination.collectionId)
            }
            .navigationDestination(for: UserDetailDestination.self) { destination in
                UserDetailView(userId: destination.userId)
            }
            .navigationDestination(for: UserPermissionsDestination.self) { destination in
                UserPermissionsView(userId: destination.userId)
            }
            .navigationDestination(for: LibrarySettingsDestination.self) { _ in
                LibrarySettingsView()
            }
            .navigationDestination(for: OrganizeSettingsDestination.self) { _ in
                OrganizeSettingsView()
            }
            .navigationDestination(for: UploadBooksDestination.self) { _ in
                UploadBooksView()
            }
            .navigationDestination(for: AdminBackupsDestination.self) { _ in
                AdminBackupsView()
            }
            .navigationDestination(for: RestoreBackupDestination.self) { destination in
                RestoreBackupView(backupId: destination.backupId)
            }
    }
}
