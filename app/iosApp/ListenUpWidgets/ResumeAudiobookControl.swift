import AppIntents
import ListenUpActivityKit
import SwiftUI
import WidgetKit

/// A Control Center / Lock Screen control (iOS 18+ `ControlWidget`) that resumes the
/// user's most-recently-played audiobook. Tapping it runs `ResumePlaybackIntent` —
/// the same intent Siri and Shortcuts use — which resolves the last-played book from
/// the app's offline-first store and starts it through the shared playback seam.
///
/// `ResumePlaybackIntent` is a `LiveActivityIntent`, so `perform()` runs in the app's
/// process (where the player lives) and `openAppWhenRun` foregrounds the app so
/// cold-start playback can begin. A play/resume button is the slice; a full transport
/// set stays out of scope.
struct ResumeAudiobookControl: ControlWidget {
    var body: some ControlWidgetConfiguration {
        StaticControlConfiguration(kind: "com.calypsan.listenup.client.ResumeAudiobook") {
            ControlWidgetButton(action: ResumePlaybackIntent()) {
                Label("Resume", systemImage: "play.fill")
            }
        }
        .displayName("Resume Audiobook")
        .description("Resume your most recent audiobook.")
    }
}
