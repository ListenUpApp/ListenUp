import Foundation
import SwiftUI
import Shared

/// Dependency container wrapping Koin for SwiftUI-native injection: a single,
/// typed access point to shared-module dependencies, with environment injection.
@Observable
final class Dependencies {
    static let shared = Dependencies()

    private init() {}

    // MARK: - Cached resolution

    private var cache: [String: Any] = [:]

    private func resolve<T>(_ factory: () -> T) -> T {
        let key = String(describing: T.self)
        if let cached = cache[key] as? T { return cached }
        let instance = factory()
        cache[key] = instance
        return instance
    }

    // MARK: - Use cases

    var getInstanceUseCase: GetInstanceUseCase { resolve { KoinHelper.shared.getInstanceUseCase() } }

    // MARK: - ViewModels (singletons)

    var serverConnectViewModel: ServerConnectViewModel { resolve { KoinHelper.shared.getServerConnectViewModel() } }
    var loginViewModel: LoginViewModel { resolve { KoinHelper.shared.getLoginViewModel() } }
    var registerViewModel: RegisterViewModel { resolve { KoinHelper.shared.getRegisterViewModel() } }
    var serverSelectViewModel: ServerSelectViewModel { resolve { KoinHelper.shared.getServerSelectViewModel() } }
    var libraryViewModel: LibraryViewModel { resolve { KoinHelper.shared.getLibraryViewModel() } }

    // MARK: - Settings

    var authSession: any AuthSession_ { resolve { KoinHelper.shared.getAuthSession() } }
    var serverConfig: ServerConfig { resolve { KoinHelper.shared.getServerConfig() } }

    // MARK: - Playback / library services

    var playbackManager: PlaybackManager { resolve { KoinHelper.shared.getPlaybackManager() } }
    var audioPlayer: AudioPlayer { resolve { KoinHelper.shared.getAudioPlayer() } }
    var bookRepository: BookRepository { resolve { KoinHelper.shared.getBookRepository() } }
    var imageStorage: ImageStorage { resolve { KoinHelper.shared.getImageStorage() } }
    var sleepTimerManager: SleepTimerManager { resolve { KoinHelper.shared.getSleepTimerManager() } }
    var downloadService: DownloadService { resolve { KoinHelper.shared.getDownloadService() } }

    // MARK: - Detail ViewModels (fresh instance per screen)

    func createBookDetailViewModel() -> BookDetailViewModel { KoinHelper.shared.getBookDetailViewModel() }
    func createSeriesDetailViewModel() -> SeriesDetailViewModel { KoinHelper.shared.getSeriesDetailViewModel() }
    func createContributorDetailViewModel() -> ContributorDetailViewModel { KoinHelper.shared.getContributorDetailViewModel() }
}

// MARK: - SwiftUI environment

private struct DependenciesKey: EnvironmentKey {
    static let defaultValue = Dependencies.shared
}

extension EnvironmentValues {
    var dependencies: Dependencies {
        get { self[DependenciesKey.self] }
        set { self[DependenciesKey.self] = newValue }
    }
}
