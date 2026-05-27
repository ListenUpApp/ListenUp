#if os(iOS)
import AppIntents

/// Toggles play/pause from the Live Activity. `LiveActivityIntent.perform()`
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
#endif
