import Foundation
import os
import SwiftUI
@preconcurrency import Shared

/// Dependency container wrapping Koin for SwiftUI-native injection: a single,
/// typed access point to shared-module dependencies, with environment injection.
@Observable
final class Dependencies: Sendable {
    static let shared = Dependencies()

    private init() {}

    // MARK: - Cached resolution

    private let lockedCache = LockedCache()

    private func resolve<T>(_ factory: () -> T) -> T {
        lockedCache.resolve(key: String(describing: T.self), factory)
    }

    // MARK: - Use cases

    var instanceRepository: InstanceRepository { resolve { KoinHelper.shared.getInstanceRepository() } }

    // MARK: - Screen-scoped ViewModels (fresh per screen)

    // A `make…`/`create…` ViewModel wraps a bare Koin `factory`: fresh per call, owned by the one
    // observer it is handed to, which passes it to `bridge.own` so it is cleared when the observer is
    // released (there is no ViewModelStore on iOS to call `onCleared`). They must be resolved FRESH
    // each time: a `resolve {}`-memoized instance, once cleared on the first dismiss, would be handed
    // back to the next screen already torn down. The three properties under "singletons" below are
    // the opposite — shared for the life of the process, and never owned. `make*` signals factory
    // semantics (mirrors `makePendingApprovalViewModel`).
    func makeServerConnectViewModel() -> ServerConnectViewModel { KoinHelper.shared.getServerConnectViewModel() }
    func makeLoginViewModel() -> LoginViewModel { KoinHelper.shared.getLoginViewModel() }
    func makeForgotPasswordViewModel() -> ForgotPasswordViewModel { KoinHelper.shared.getForgotPasswordViewModel() }
    func makeRegisterViewModel() -> RegisterViewModel { KoinHelper.shared.getRegisterViewModel() }
    func makeSetupViewModel() -> SetupViewModel { KoinHelper.shared.getSetupViewModel() }
    func makeClaimInviteViewModel() -> ClaimInviteViewModel { KoinHelper.shared.getClaimInviteViewModel() }
    func makeServerSelectViewModel() -> ServerSelectViewModel { KoinHelper.shared.getServerSelectViewModel() }
    func makeLibrarySetupViewModel() -> LibrarySetupViewModel { KoinHelper.shared.getLibrarySetupViewModel() }
    func makeHomeViewModel() -> HomeViewModel { KoinHelper.shared.getHomeViewModel() }
    func makeLibraryViewModel() -> LibraryViewModel { KoinHelper.shared.getLibraryViewModel() }

    // MARK: - ViewModels (singletons)

    // view-model-owner: nobody — these three are shared for the process and never cleared, because
    // the next screen receives the same instance.
    var libraryViewModel: LibraryViewModel { resolve { KoinHelper.shared.getLibraryViewModel() } }
    var syncRepository: any SyncRepository { resolve { KoinHelper.shared.getSyncRepository() } }
    var homeStatsViewModel: HomeStatsViewModel { resolve { KoinHelper.shared.getHomeStatsViewModel() } }
    var searchViewModel: SearchViewModel { resolve { KoinHelper.shared.getSearchViewModel() } }

    // MARK: - Settings

    var authSession: any AuthSession { resolve { KoinHelper.shared.getAuthSession() } }
    /// The shared error bus. Memoized — it is a Koin `single`, and a fresh instance per read would
    /// be a bus with no emitters on it.
    var errorBus: ErrorBus { resolve { KoinHelper.shared.getErrorBus() } }
    var serverConfig: ServerConfig { resolve { KoinHelper.shared.getServerConfig() } }
    var deepLinkManager: DeepLinkManager { resolve { KoinHelper.shared.getDeepLinkManager() } }

    // MARK: - Playback seam (consumed by PlayerCoordinator)

    /// The shared player's engine seam: `PlayerCoordinator` executes its commands and reports back into it.
    var nativePlayerEngine: NativePlayerEngine { resolve { KoinHelper.shared.getNativePlayerEngine() } }
    var playbackProgressReporter: PlaybackProgressReporter { resolve { KoinHelper.shared.getPlaybackProgressReporter() } }
    var playbackPreferences: PlaybackPreferences { resolve { KoinHelper.shared.getPlaybackPreferences() } }

    // MARK: - Library services

    var bookRepository: BookRepository { resolve { KoinHelper.shared.getBookRepository() } }
    var homeRepository: HomeRepository { resolve { KoinHelper.shared.getHomeRepository() } }
    var imageStorage: ImageStorage { resolve { KoinHelper.shared.getImageStorage() } }
    var imageRepository: ImageRepository { resolve { KoinHelper.shared.getImageRepository() } }
    var userProfileRepository: UserProfileRepository { resolve { KoinHelper.shared.getUserProfileRepository() } }
    var downloadService: DownloadService { resolve { KoinHelper.shared.getDownloadService() } }
    var playbackBandwidthCoordinator: PlaybackBandwidthCoordinator {
        resolve { KoinHelper.shared.getPlaybackBandwidthCoordinator() }
    }

    // MARK: - Player coordinator (app-wide Swift singleton)

    private let playerSlot = PlayerCoordinatorSlot()

    /// The single app-wide player orchestrator. Built lazily on first access,
    /// injecting the KMP seam from Koin.
    @MainActor var playerCoordinator: PlayerCoordinator {
        if let coordinator = playerSlot.coordinator { return coordinator }
        let coordinator = PlayerCoordinator(deps: self)
        playerSlot.coordinator = coordinator
        return coordinator
    }

    private let observerSlot = PlayerObserverSlot()

    /// The app-wide SwiftUI model of the shared player. Built lazily, with the coordinator, so the shared
    /// player's instructions are drained from the first read. App-lifetime, so its `PlayerViewModel` is not owned.
    @MainActor var playerObserver: PlayerObserver {
        if let observer = observerSlot.observer { return observer }
        let observer = PlayerObserver(viewModel: KoinHelper.shared.getPlayerViewModel(), coordinator: playerCoordinator)
        observerSlot.observer = observer
        return observer
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
    func createBookHardcoverViewModel(bookId: String) -> BookHardcoverViewModel {
        KoinHelper.shared.getBookHardcoverViewModel(bookId: bookId)
    }
    func createBookRatingsViewModel(bookId: String) -> BookRatingsViewModel {
        KoinHelper.shared.getBookRatingsViewModel(bookId: bookId)
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
    /// The chapter editor for exactly this book. Parametrized, so a fresh instance per book —
    /// the editor never switches the book it edits.
    func createChapterEditorViewModel(bookId: String) -> ChapterEditorViewModel {
        KoinHelper.shared.getChapterEditorViewModel(bookId: bookId)
    }
    /// A bulk metadata editor for exactly these books. Parametrized, so a fresh instance per
    /// selection — the editor never switches the books it edits.
    func createBulkEditViewModel(bookIds: [String]) -> BulkEditViewModel {
        KoinHelper.shared.getBulkEditViewModel(bookIds: bookIds)
    }
    /// Match details for exactly this book. Parametrized, so a fresh instance per book.
    func createBookMatchViewModel(bookId: String) -> BookMatchViewModel {
        KoinHelper.shared.getBookMatchViewModel(bookId: bookId)
    }
    /// Match details for exactly this person. Parametrized, so a fresh instance per contributor.
    func createPersonMatchViewModel(contributorId: String) -> PersonMatchViewModel {
        KoinHelper.shared.getPersonMatchViewModel(contributorId: contributorId)
    }
    /// The match receipt for exactly this book or person, keyed by its id.
    func createMatchReceiptViewModel(subjectId: String) -> MatchReceiptViewModel {
        KoinHelper.shared.getMatchReceiptViewModel(subjectId: subjectId)
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
    func createMyShelvesViewModel() -> MyShelvesViewModel { KoinHelper.shared.getMyShelvesViewModel() }
    func createCreateEditShelfViewModel() -> CreateEditShelfViewModel { KoinHelper.shared.getCreateEditShelfViewModel() }
    func createSettingsViewModel() -> SettingsViewModel { KoinHelper.shared.getSettingsViewModel() }
    func createDevicesViewModel() -> DevicesViewModel { KoinHelper.shared.getDevicesViewModel() }
    func createHardcoverSettingsViewModel() -> HardcoverSettingsViewModel {
        KoinHelper.shared.getHardcoverSettingsViewModel()
    }
    /// The books kept off Hardcover (#1541). A fresh instance per visit to the list.
    func createKeptOffBooksViewModel() -> KeptOffBooksViewModel {
        KoinHelper.shared.getKeptOffBooksViewModel()
    }
    /// Find on Hardcover for exactly this book. Parametrized, so a fresh instance per sheet.
    func createHardcoverMatchViewModel(bookId: String) -> HardcoverMatchViewModel {
        KoinHelper.shared.getHardcoverMatchViewModel(bookId: bookId)
    }
    func createNotificationBellViewModel() -> NotificationBellViewModel {
        KoinHelper.shared.getNotificationBellViewModel()
    }
    func createInboxBadgeViewModel() -> InboxBadgeViewModel {
        KoinHelper.shared.getInboxBadgeViewModel()
    }
    func createRestrictedBooksViewModel() -> RestrictedBooksViewModel {
        KoinHelper.shared.getRestrictedBooksViewModel()
    }
    func createNotificationsViewModel() -> NotificationsViewModel {
        KoinHelper.shared.getNotificationsViewModel()
    }
    func createNotificationPrefsViewModel() -> NotificationPrefsViewModel {
        KoinHelper.shared.getNotificationPrefsViewModel()
    }

    func createAdminViewModel() -> AdminViewModel { KoinHelper.shared.getAdminViewModel() }
    func createAdminInboxViewModel() -> AdminInboxViewModel { KoinHelper.shared.getAdminInboxViewModel() }
    func createAdminSettingsViewModel() -> AdminSettingsViewModel { KoinHelper.shared.getAdminSettingsViewModel() }
    func createLibrarySettingsViewModel() -> LibrarySettingsViewModel { KoinHelper.shared.getLibrarySettingsViewModel() }
    func createCreateInviteViewModel() -> CreateInviteViewModel { KoinHelper.shared.getCreateInviteViewModel() }
    func createABSImportHubViewModel() -> ABSImportHubViewModel { KoinHelper.shared.getABSImportHubViewModel() }
    func createImportFlowViewModel() -> ImportFlowViewModel { KoinHelper.shared.getImportFlowViewModel() }
    func createAdminBackupViewModel() -> AdminBackupViewModel { KoinHelper.shared.getAdminBackupViewModel() }
    func createOrganizeSettingsViewModel() -> OrganizeSettingsViewModel {
        KoinHelper.shared.getOrganizeSettingsViewModel()
    }
    func createUploadBooksViewModel() -> UploadBooksViewModel { KoinHelper.shared.getUploadBooksViewModel() }
    func createRestoreFromFileViewModel() -> RestoreFromFileViewModel { KoinHelper.shared.getRestoreFromFileViewModel() }
    func createRestoreBackupViewModel(backupId: String) -> RestoreBackupViewModel {
        KoinHelper.shared.getRestoreBackupViewModel(backupId: backupId)
    }
    func createAdminCollectionsViewModel() -> AdminCollectionsViewModel { KoinHelper.shared.getAdminCollectionsViewModel() }
    func createAdminCategoriesViewModel() -> AdminCategoriesViewModel { KoinHelper.shared.getAdminCategoriesViewModel() }
    func createAdminCollectionDetailViewModel(collectionId: String) -> AdminCollectionDetailViewModel {
        KoinHelper.shared.getAdminCollectionDetailViewModel(collectionId: collectionId)
    }

    func createUserDetailViewModel(userId: String) -> UserDetailViewModel {
        KoinHelper.shared.getUserDetailViewModel(userId: userId)
    }

    func createUserPermissionsViewModel(userId: String) -> UserPermissionsViewModel {
        KoinHelper.shared.getUserPermissionsViewModel(userId: userId)
    }
}

/// Holds the app's one `PlayerCoordinator`, on the main actor, so that `Dependencies` itself has no
/// mutable state and can be shared across isolation domains.
@MainActor
private final class PlayerCoordinatorSlot {
    var coordinator: PlayerCoordinator?
    nonisolated init() {}
}

/// Holds the app's one `PlayerObserver`, on the main actor, like `PlayerCoordinatorSlot`.
@MainActor
private final class PlayerObserverSlot {
    var observer: PlayerObserver?
    nonisolated init() {}
}

// MARK: - Locked cache

/// Thread-safe memo cache for Koin-resolved singletons. Replaces the previous
/// unsynchronized `[String: Any]` that raced when resolved off the main actor.
final class LockedCache: Sendable {
    // Values are arbitrary Koin singletons, so the dictionary is not `Sendable`. The lock is the
    // synchronization boundary: it is only ever touched inside `withLockUnchecked`.
    private let storage = OSAllocatedUnfairLock<[String: Any]>(uncheckedState: [:])

    func resolve<T>(key: String, _ factory: () -> T) -> T {
        storage.withLockUnchecked { entries in
            if let cached = entries[key] as? T { return cached }
            let instance = factory()
            entries[key] = instance
            return instance
        }
    }

    var count: Int { storage.withLockUnchecked { $0.count } }
}

// MARK: - SwiftUI environment

extension EnvironmentValues {
    @Entry var dependencies: Dependencies = .shared
}
