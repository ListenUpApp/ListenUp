import SwiftUI

/// Speaks a sentence to VoiceOver without moving focus — the native way to confirm something that
/// changed out of the user's line of sight (a copy, a bulk add that closed its sheet).
///
/// High priority, because these are posted as a sheet closes or a list reshuffles, and a
/// default-priority announcement is cut off by the screen-change notification that follows. HIG,
/// VoiceOver; Feedback ("Make sure all feedback is accessible").
enum VoiceOverAnnouncement {
    static func post(_ message: String) {
        var text = AttributedString(message)
        text.accessibilitySpeechAnnouncementPriority = .high
        AccessibilityNotification.Announcement(text).post()
    }
}
