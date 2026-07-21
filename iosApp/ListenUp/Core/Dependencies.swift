import Foundation
import os
import SwiftUI
@preconcurrency import Shared

/// Dependency container wrapping Koin for SwiftUI-native injection: a single,
/// typed access point to shared-module dependencies, with environment injection.
@Observable
final class Dependencies {
    nonisolated(unsafe) static let shared = Dependencies()

    private init() {}

    // MARK: - Cached resolution

    private let lockedCache = LockedCache()

    private func resolve<T>(_ factory: () -> T) -> T {
        lockedCache.resolve(key: String(describing: T.self), factory)
    }

    // MARK: - Use cases

    var instanceRepository: InstanceRepository { resolve { KoinHelper.shared.getInstanceRepository() } }

    // MARK: - ViewModels (singletons)

    var serverConnectViewModel: ServerConnectViewModel { resolve { KoinHelper.shared.getServerConnectViewModel() } }
    var loginViewModel: LoginViewModel { resolve { KoinHelper.shared.getLoginViewModel() } }
    var registerViewModel: RegisterViewModel { resolve { KoinHelper.shared.getRegisterViewModel() } }
    var setupViewModel: SetupViewModel { resolve { KoinHelper.shared.getSetupViewModel() } }
    var claimInviteViewModel: ClaimInviteViewModel { resolve { KoinHelper.shared.getClaimInviteViewModel() } }
    var serverSelectViewModel: ServerSelectViewModel { resolve { KoinHelper.shared.getServerSelectViewModel() } }
    var librarySetupViewModel: LibrarySetupViewModel { resolve { KoinHelper.shared.getLibrarySetupViewModel() } }
    var libraryViewModel: LibraryViewModel { resolve { KoinHelper.shared.getLibraryViewModel() } }
    var syncRepository: any SyncRepository { resolve { KoinHelper.shared.getSyncRepository() } }
    var homeViewModel: HomeViewModel { resolve { KoinHelper.shared.getHomeViewModel() } }
    var homeStatsViewModel: HomeStatsViewModel { resolve { KoinHelper.shared.getHomeStatsViewModel() } }
    var searchViewModel: SearchViewModel { resolve { KoinHelper.shared.getSearchViewModel() } }

    // MARK: - Settings

    var authSession: any AuthSession { resolve { KoinHelper.shared.getAuthSession() } }
    var serverConfig: ServerConfig { resolve { KoinHelper.shared.getServerConfig() } }
    var deepLinkManager: DeepLinkManager { resolve { KoinHelper.shared.getDeepLinkManager() } }

    // MARK: - Playback seam (consumed by PlayerCoordinator)

    var playbackPreparer: PlaybackPreparer { resolve { KoinHelper.shared.getPlaybackPreparer() } }
    var playbackProgressReporter: PlaybackProgressReporter { resolve { KoinHelper.shared.getPlaybackProgressReporter() } }
    var sleepTimerManager: SleepTimerManager { resolve { KoinHelper.shared.getSleepTimerManager() } }
    var playbackPreferences: PlaybackPreferences { resolve { KoinHelper.shared.getPlaybackPreferences() } }

    // MARK: - Library services

    var bookRepository: BookRepository { resolve { KoinHelper.shared.getBookRepository() } }
    var homeRepository: HomeRepository { resolve { KoinHelper.shared.getHomeRepository() } }
    var documentRepository: DocumentRepository { resolve { KoinHelper.shared.getDocumentRepository() } }
    var imageStorage: ImageStorage { resolve { KoinHelper.shared.getImageStorage() } }
    var imageRepository: ImageRepository { resolve { KoinHelper.shared.getImageRepository() } }
    var userProfileRepository: UserProfileRepository { resolve { KoinHelper.shared.getUserProfileRepository() } }
    var downloadService: DownloadService { resolve { KoinHelper.shared.getDownloadService() } }
    var serverReachability: ServerReachability { resolve { KoinHelper.shared.getServerReachability() } }
    var playbackBandwidthCoordinator: PlaybackBandwidthCoordinator {
        resolve { KoinHelper.shared.getPlaybackBandwidthCoordinator() }
    }

    // MARK: - Player coordinator (app-wide Swift singleton)

    @MainActor private var cachedPlayerCoordinator: PlayerCoordinator?

    /// The single app-wide player orchestrator. Built lazily on first access,
    /// injecting the KMP seam from Koin.
    @MainActor var playerCoordinator: PlayerCoordinator {
        if let cachedPlayerCoordinator { return cachedPlayerCoordinator }
        let coordinator = PlayerCoordinator(deps: self)
        cachedPlayerCoordinator = coordinator
        return coordinator
    }

    // MARK: - Detail ViewModels (fresh instance per screen)

    /// Parametrized VM — a fresh instance per pending-approval session (carries userId/email + its SSE job).
    func makePendingApprovalViewModel(userId: String, email: String) -> PendingApprovalViewModel {
        KoinHelper.shared.getPendingApprovalViewModel(userId: userId, email: email)
    }

    func createBookDetailViewModel() -> BookDetailViewModel { KoinHelper.shared.getBookDetailViewModel() }
    func createBookMultiSelectViewModel() -> BookMultiSelectViewModel {
        KoinHelper.shared.getBookMultiSelectViewModel()
    }
    func createBookReadersViewModel(bookId: String) -> BookReadersViewModel {
        KoinHelper.shared.getBookReadersViewModel(bookId: bookId)
    }
    func createSeriesDetailViewModel() -> SeriesDetailViewModel { KoinHelper.shared.getSeriesDetailViewModel() }
    func createSeriesEditViewModel() -> SeriesEditViewModel { KoinHelper.shared.getSeriesEditViewModel() }
    func createContributorDetailViewModel() -> ContributorDetailViewModel {
        KoinHelper.shared.getContributorDetailViewModel()
    }
    func createContributorEditViewModel() -> ContributorEditViewModel {
        KoinHelper.shared.getContributorEditViewModel()
    }
    func createContributorBooksViewModel() -> ContributorBooksViewModel {
        KoinHelper.shared.getContributorBooksViewModel()
    }
    func createStorageViewModel() -> StorageViewModel { KoinHelper.shared.getStorageViewModel() }
    func createSyncIndicatorViewModel() -> SyncIndicatorViewModel {
        KoinHelper.shared.getSyncIndicatorViewModel()
    }
    func createBookEditViewModel() -> BookEditViewModel { KoinHelper.shared.getBookEditViewModel() }
    func createMetadataViewModel() -> MetadataViewModel { KoinHelper.shared.getMetadataViewModel() }
    func createContributorMetadataViewModel() -> ContributorMetadataViewModel {
        KoinHelper.shared.getContributorMetadataViewModel()
    }

    func createDiscoverViewModel() -> DiscoverViewModel { KoinHelper.shared.getDiscoverViewModel() }
    func createLeaderboardViewModel() -> LeaderboardViewModel { KoinHelper.shared.getLeaderboardViewModel() }
    func createActivityFeedViewModel() -> ActivityFeedViewModel { KoinHelper.shared.getActivityFeedViewModel() }
    func createSeeAllSearchViewModel() -> SeeAllSearchViewModel { KoinHelper.shared.getSeeAllSearchViewModel() }

    func createBrowseFacetViewModel() -> BrowseFacetViewModel { KoinHelper.shared.getBrowseFacetViewModel() }
    func createGenreDestinationViewModel() -> GenreDestinationViewModel {
        KoinHelper.shared.getGenreDestinationViewModel()
    }
    func createUserProfileViewModel() -> UserProfileViewModel { KoinHelper.shared.getUserProfileViewModel() }
    func createEditProfileViewModel() -> EditProfileViewModel { KoinHelper.shared.getEditProfileViewModel() }
    func createShelfDetailViewModel() -> ShelfDetailViewModel { KoinHelper.shared.getShelfDetailViewModel() }
    func createCreateEditShelfViewModel() -> CreateEditShelfViewModel { KoinHelper.shared.getCreateEditShelfViewModel() }
    func createSettingsViewModel() -> SettingsViewModel { KoinHelper.shared.getSettingsViewModel() }
    func createDevicesViewModel() -> DevicesViewModel { KoinHelper.shared.getDevicesViewModel() }

    func createAdminViewModel() -> AdminViewModel { KoinHelper.shared.getAdminViewModel() }
    func createAdminInboxViewModel() -> AdminInboxViewModel { KoinHelper.shared.getAdminInboxViewModel() }
    func createAdminSettingsViewModel() -> AdminSettingsViewModel { KoinHelper.shared.getAdminSettingsViewModel() }
    func createLibrarySettingsViewModel() -> LibrarySettingsViewModel { KoinHelper.shared.getLibrarySettingsViewModel() }
    func createCreateInviteViewModel() -> CreateInviteViewModel { KoinHelper.shared.getCreateInviteViewModel() }
    func createABSImportHubViewModel() -> ABSImportHubViewModel { KoinHelper.shared.getABSImportHubViewModel() }
    func createImportFlowViewModel() -> ImportFlowViewModel { KoinHelper.shared.getImportFlowViewModel() }
    func createAdminBackupViewModel() -> AdminBackupViewModel { KoinHelper.shared.getAdminBackupViewModel() }
    func createRestoreFromFileViewModel() -> RestoreFromFileViewModel { KoinHelper.shared.getRestoreFromFileViewModel() }
    func createRestoreBackupViewModel(backupId: String) -> RestoreBackupViewModel {
        KoinHelper.shared.getRestoreBackupViewModel(backupId: backupId)
    }
    func createAdminCollectionsViewModel() -> AdminCollectionsViewModel { KoinHelper.shared.getAdminCollectionsViewModel() }
    func createAdminCollectionDetailViewModel(collectionId: String) -> AdminCollectionDetailViewModel {
        KoinHelper.shared.getAdminCollectionDetailViewModel(collectionId: collectionId)
    }

    func createUserDetailViewModel(userId: String) -> UserDetailViewModel {
        KoinHelper.shared.getUserDetailViewModel(userId: userId)
    }
}

// MARK: - Locked cache

/// Thread-safe memo cache for Koin-resolved singletons. Replaces the previous
/// unsynchronized `[String: Any]` that raced when resolved off the main actor.
final class LockedCache: Sendable {
    /// The type-erased `[String: Any]` store can't be `Sendable` (its values are
    /// arbitrary Koin singletons). The lock is the synchronization boundary, so the
    /// dictionary is only ever touched while held — the `@unchecked` is sound.
    private struct Storage: @unchecked Sendable {
        var entries: [String: Any] = [:]
    }

    private let storage = OSAllocatedUnfairLock(initialState: Storage())

    func resolve<T>(key: String, _ factory: () -> T) -> T {
        storage.withLockUnchecked { storage in
            if let cached = storage.entries[key] as? T { return cached }
            let instance = factory()
            storage.entries[key] = instance
            return instance
        }
    }

    var count: Int { storage.withLockUnchecked { $0.entries.count } }
}

// MARK: - SwiftUI environment

extension EnvironmentValues {
    @Entry var dependencies: Dependencies = .shared
}
