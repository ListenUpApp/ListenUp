import ListenUpActivityKit
@preconcurrency import Shared

/// The app's `LastPlayedBookProviding` implementation — the loaded book first (spec §4.4), then
/// `HomeRepository.getResumeBookOrNull()`, the same offline-first "Continue Listening" source the Home
/// screen renders. Its `bookId` feeds `ResumePlaybackIntent`.
/// Registered with `AppDependencyManager` at launch (see `ListenUpApp`).
@MainActor
struct LastPlayedBookProvider: LastPlayedBookProviding {

    func mostRecentBookId() async -> String? {
        // The loaded book first: Control Center's Resume and the App Intents resume what is loaded, never
        // resurrect a book the reader closed. Then the Continue Listening fallback, as before. Both are
        // plain-typed accessors (AppResult is folded in Kotlin, never awaited across the bridge); nil from
        // both → the intent speaks "nothing to resume". Never stranded.
        if let loaded = try? await KoinHelper.shared.loadedBookIdOrNull() { return loaded }
        return (try? await Dependencies.shared.homeRepository.getResumeBookOrNull())?.bookId
    }
}
