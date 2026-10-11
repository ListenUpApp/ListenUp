import Foundation

/// The primary button's words and state (spec §2.9). Preparing keeps the stage's word and shows a busy indicator,
/// as `ActionLabel` already does, so VoiceOver still hears what the button does, with a "Loading" value.
struct BookPrimaryActionLabel: Equatable {
    let key: String
    let isBusy: Bool
    let isEnabled: Bool
    let systemImage: String

    init(kind: BookPrimaryActionKind, isPreparing: Bool) {
        switch kind {
        case .play: key = "book.detail_play_ios"
        case .resume: key = "book.detail_resume_ios"
        case .listenAgain: key = "book.detail_listen_again_ios"
        case .unavailableOffline: key = "book.detail_unavailable_offline"
        }
        isBusy = isPreparing
        // Spec N8: Unavailable offline is the only disabled primary; Resume and Listen Again stay live.
        isEnabled = kind != .unavailableOffline
        systemImage = kind == .unavailableOffline ? "cloud.slash.fill" : "play.fill"
    }

    /// The localized title. Every key is a literal here so `verifySwiftStringKeys` sees it.
    var title: String {
        switch key {
        case "book.detail_play_ios": String(localized: "book.detail_play_ios")
        case "book.detail_resume_ios": String(localized: "book.detail_resume_ios")
        case "book.detail_listen_again_ios": String(localized: "book.detail_listen_again_ios")
        default: String(localized: "book.detail_unavailable_offline")
        }
    }
}
