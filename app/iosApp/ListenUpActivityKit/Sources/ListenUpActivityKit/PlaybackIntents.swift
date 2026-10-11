#if os(iOS)
import AppIntents

/// Toggles play/pause from Siri or Shortcuts. `LiveActivityIntent.perform()`
/// runs in the app's process, where `playback` resolves to the app's player.
public struct TogglePlaybackIntent: LiveActivityIntent {
    public static let title: LocalizedStringResource = "Play or Pause"

    @Dependency public var playback: any PlaybackControlling

    public init() {}

    public func perform() async throws -> some IntentResult {
        await playback.togglePlayPause()
        return .result()
    }
}

/// Skips playback forward by the user's configured interval.
public struct SkipForwardIntent: LiveActivityIntent {
    public static let title: LocalizedStringResource = "Skip Forward"

    @Dependency public var playback: any PlaybackControlling

    public init() {}

    public func perform() async throws -> some IntentResult {
        await playback.skipForward()
        return .result()
    }
}

/// Skips playback backward by the user's configured interval.
public struct SkipBackwardIntent: LiveActivityIntent {
    public static let title: LocalizedStringResource = "Skip Backward"

    @Dependency public var playback: any PlaybackControlling

    public init() {}

    public func perform() async throws -> some IntentResult {
        await playback.skipBackward()
        return .result()
    }
}

/// Resumes the user's most-recently-played in-progress audiobook — "Resume my book
/// in ListenUp" (no book name) and the Control Center / Lock Screen resume button.
///
/// Reads the last-played book id from the app's offline-first store (the same
/// "Continue Listening" source the Home screen renders) via `LastPlayedBookProviding`,
/// then starts it through the shared `PlaybackControlling` seam — both dependencies
/// the app registers with `AppDependencyManager` at launch.
///
/// A `LiveActivityIntent` (not `AudioPlaybackIntent`): like the other package controls,
/// `perform()` runs in the app's process, so the Control Center button reaches the real
/// player. `openAppWhenRun` opens the app so cold-start playback can foreground the
/// player; when there is nothing to resume it throws a spoken, user-facing error.
public struct ResumePlaybackIntent: LiveActivityIntent {
    public static let title: LocalizedStringResource = "Resume Audiobook"
    public static let openAppWhenRun = true

    @Dependency public var playback: any PlaybackControlling
    @Dependency public var lastPlayed: any LastPlayedBookProviding

    public init() {}

    @MainActor
    public func perform() async throws -> some IntentResult {
        guard let bookId = await lastPlayed.mostRecentBookId() else {
            throw ResumePlaybackError.nothingToResume
        }
        playback.playBook(id: bookId)
        return .result()
    }
}

/// "Set a sleep timer in ListenUp" — a countdown of `minutes` (default 30, as on Android).
///
/// With nothing loaded it first resumes the most recent book, as the Android launcher shortcut
/// does. No waiting is needed: `PlayerCoordinator.play(bookId:)` retargets the player (and its
/// sleep timer) synchronously on the main actor, so the timer set right after belongs to the
/// resumed book. With nothing to resume it throws the same spoken error as `ResumePlaybackIntent`
/// and sets no timer.
public struct SleepTimerIntent: LiveActivityIntent {
    public static let title: LocalizedStringResource = "Set Sleep Timer"
    public static let openAppWhenRun = true

    @Parameter(title: "Minutes", default: 30, inclusiveRange: (1, 720))
    public var minutes: Int

    @Dependency public var playback: any PlaybackControlling
    @Dependency public var lastPlayed: any LastPlayedBookProviding

    public init() {}

    @MainActor
    public func perform() async throws -> some IntentResult {
        if !playback.hasLoadedBook {
            guard let bookId = await lastPlayed.mostRecentBookId() else {
                throw ResumePlaybackError.nothingToResume
            }
            playback.playBook(id: bookId)
        }
        playback.setSleepTimer(minutes: minutes)
        return .result()
    }
}

/// Failure surfaced to Siri / Shortcuts / Control Center when resume is requested but
/// the user has no in-progress book. `errorDescription` is what Siri speaks.
public enum ResumePlaybackError: Swift.Error, LocalizedError, Equatable {
    case nothingToResume

    public var errorDescription: String? {
        switch self {
        case .nothingToResume:
            return "There's nothing to resume. Start a book first."
        }
    }
}
#endif
