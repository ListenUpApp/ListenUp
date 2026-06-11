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

    var getInstanceUseCase: GetInstanceUseCase { resolve { KoinHelper.shared.getInstanceUseCase() } }

    // MARK: - ViewModels (singletons)

    var serverConnectViewModel: ServerConnectViewModel { resolve { KoinHelper.shared.getServerConnectViewModel() } }
    var loginViewModel: LoginViewModel { resolve { KoinHelper.shared.getLoginViewModel() } }
    var registerViewModel: RegisterViewModel { resolve { KoinHelper.shared.getRegisterViewModel() } }
    var serverSelectViewModel: ServerSelectViewModel { resolve { KoinHelper.shared.getServerSelectViewModel() } }
    var librarySetupViewModel: LibrarySetupViewModel { resolve { KoinHelper.shared.getLibrarySetupViewModel() } }
    var libraryViewModel: LibraryViewModel { resolve { KoinHelper.shared.getLibraryViewModel() } }
    var syncRepository: any SyncRepository { resolve { KoinHelper.shared.getSyncRepository() } }

    // MARK: - Settings

    var authSession: any AuthSession_ { resolve { KoinHelper.shared.getAuthSession() } }
    var serverConfig: ServerConfig { resolve { KoinHelper.shared.getServerConfig() } }

    // MARK: - Playback seam (consumed by PlayerCoordinator)

    var playbackPreparer: PlaybackPreparer { resolve { KoinHelper.shared.getPlaybackPreparer() } }
    var progressTracker: ProgressTracker { resolve { KoinHelper.shared.getProgressTracker() } }
    var sleepTimerManager: SleepTimerManager { resolve { KoinHelper.shared.getSleepTimerManager() } }

    // MARK: - Library services

    var bookRepository: BookRepository { resolve { KoinHelper.shared.getBookRepository() } }
    var imageStorage: ImageStorage { resolve { KoinHelper.shared.getImageStorage() } }
    var downloadService: DownloadService { resolve { KoinHelper.shared.getDownloadService() } }

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

    func createBookDetailViewModel() -> BookDetailViewModel { KoinHelper.shared.getBookDetailViewModel() }
    func createSeriesDetailViewModel() -> SeriesDetailViewModel { KoinHelper.shared.getSeriesDetailViewModel() }
    func createContributorDetailViewModel() -> ContributorDetailViewModel { KoinHelper.shared.getContributorDetailViewModel() }
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

private struct DependenciesKey: EnvironmentKey {
    nonisolated(unsafe) static let defaultValue = Dependencies.shared
}

extension EnvironmentValues {
    var dependencies: Dependencies {
        get { self[DependenciesKey.self] }
        set { self[DependenciesKey.self] = newValue }
    }
}
