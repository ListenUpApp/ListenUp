import AppIntents
import ListenUpActivityKit

/// The app's Siri / Spotlight / Shortcuts surface. Every phrase includes the
/// `\(.applicationName)` token (required by App Intents) so "Play Dungeon
/// Crawler Carl in ListenUp" and the playback-control phrases route here.
///
/// Playback control reuses the package `LiveActivityIntent`s — they conform to
/// `AppIntent` and resolve the same `PlaybackControlling` dependency in the app's
/// process, so there is no need for app-target wrapper intents.
struct ListenUpShortcuts: AppShortcutsProvider {
    static var appShortcuts: [AppShortcut] {
        AppShortcut(
            intent: PlayBookIntent(),
            phrases: [
                "Play \(\.$target) in \(.applicationName)",
                "Play \(\.$target) on \(.applicationName)"
            ],
            shortTitle: "Play Audiobook",
            systemImageName: "play.circle.fill"
        )
        AppShortcut(
            intent: ResumePlaybackIntent(),
            phrases: [
                "Resume my book in \(.applicationName)",
                "Continue my audiobook in \(.applicationName)"
            ],
            shortTitle: "Resume Audiobook",
            systemImageName: "play.fill"
        )
        AppShortcut(
            intent: TogglePlaybackIntent(),
            phrases: [
                "Pause \(.applicationName)"
            ],
            shortTitle: "Play or Pause",
            systemImageName: "playpause.fill"
        )
        AppShortcut(
            intent: SkipForwardIntent(),
            phrases: ["Skip forward in \(.applicationName)"],
            shortTitle: "Skip Forward",
            systemImageName: "goforward"
        )
        AppShortcut(
            intent: SkipBackwardIntent(),
            phrases: ["Skip back in \(.applicationName)"],
            shortTitle: "Skip Backward",
            systemImageName: "gobackward"
        )
    }
}
