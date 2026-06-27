import ListenUpActivityKit
@preconcurrency import Shared

/// The app's `LastPlayedBookProviding` implementation — reads
/// `HomeRepository.getResumeBookOrNull()`, the same offline-first "Continue
/// Listening" source the Home screen renders (it fetches a window and takes the
/// first). The top item is, by definition, "resume my book"; its `bookId` feeds
/// `ResumePlaybackIntent`.
/// Registered with `AppDependencyManager` at launch (see `ListenUpApp`).
@MainActor
struct LastPlayedBookProvider: LastPlayedBookProviding {

    func mostRecentBookId() async -> String? {
        // iOS-safe accessor: AppResult is folded in Kotlin (never awaited across the bridge).
        // nil (failure or nothing to resume) → the intent speaks "nothing to resume". Never stranded.
        (try? await Dependencies.shared.homeRepository.getResumeBookOrNull())?.bookId
    }
}
