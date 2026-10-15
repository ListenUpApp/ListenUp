import Foundation

/// What the mini player accessory draws in one state (spec §5.1; boards `Mini-iPhone`, `Mini-iPad`, `States-iPhone`).
/// A pure value, so every state is testable without SwiftUI; `MiniPlayerAccessoryContent` only lays it out.
struct MiniPlayerAccessoryModel: Equatable {
    /// The one main button's look.
    enum Primary: Equatable {
        case play, pause, busy, close
        /// A labelled action (Retry, Sign in, Open Inbox, Listen Again, Keep Listening); `prominent` = a filled
        /// coral capsule, otherwise a tinted one.
        case pill(String, prominent: Bool)
    }

    /// What the main button does. The sleep fade's Keep Listening is the mini player's own (Simon, 2026-10-14):
    /// it cancels the timer, where the shared primary would pause.
    enum PrimaryAction: Equatable {
        case shared, keepListening
    }

    var bookId: String?
    var coverPath: String?
    var coverHash: String?
    var title: String
    /// Nil inline: the minimised tab bar leaves room for the title and the main button only.
    var line: String?
    var showsSkipBack: Bool
    var showsSkipForward: Bool
    var skipBackSec: Int
    var skipForwardSec: Int
    var primary: Primary
    var primaryAction: PrimaryAction
    var primaryLabel: String
    /// A failure the reader has to act on: the line is drawn in red with a warning glyph, on the plain system glass
    /// (no capsule tint; Simon, 2026-10-14).
    var isFailure: Bool
    /// The network dropped and the player is recovering: announced once when it starts.
    var isReconnecting: Bool
    var showsProgress: Bool
    var chapterFraction: Float

    /// What a book switch animates on: the book, never its state, so a pause or a tick never re-animates the bar.
    var switchKey: String? { bookId }

    static func make(
        snapshot: PlayerSnapshot,
        chapterRemainingMs: Int64,
        chapterFraction: Float,
        isInline: Bool,
        isWide: Bool
    ) -> MiniPlayerAccessoryModel {
        let (primary, label, action) = primary(for: snapshot)
        var isFailure = false
        if case .problem(_, _, let tinted) = snapshot.miniLine { isFailure = tinted }
        return MiniPlayerAccessoryModel(
            bookId: snapshot.currentBookId,
            coverPath: snapshot.coverPath,
            coverHash: snapshot.coverHash,
            title: snapshot.bookTitle,
            line: isInline ? nil : lineText(snapshot.miniLine, chapterRemainingMs: chapterRemainingMs),
            showsSkipBack: snapshot.showsSkipBack && !isInline,
            showsSkipForward: isWide && !isInline,
            skipBackSec: snapshot.skipBackwardSec,
            skipForwardSec: snapshot.skipForwardSec,
            primary: primary,
            primaryAction: action,
            primaryLabel: label,
            isFailure: isFailure,
            isReconnecting: snapshot.miniLine == .reconnecting,
            showsProgress: !isInline,
            chapterFraction: chapterFraction
        )
    }

    private static func primary(for snapshot: PlayerSnapshot) -> (Primary, String, PrimaryAction) {
        if snapshot.miniLine == .fadingOut {
            let text = String(localized: "player.mini_keep_listening_ios")
            return (.pill(text, prominent: false), text, .keepListening)
        }
        switch snapshot.primary {
        case .pause: return (.pause, String(localized: "player.pause"), .shared)
        case .play, .tapToPlay: return (.play, String(localized: "player.play"), .shared)
        case .busy:
            let label = snapshot.miniLine == .reconnecting
                ? String(localized: "player.mini_reconnecting_a11y")
                : String(localized: "player.mini_busy_a11y")
            return (.busy, label, .shared)
        case .retry: return labelled(String(localized: "player.retry"))
        case .signIn: return labelled(String(localized: "auth.sign_in"))
        case .openInbox: return labelled(String(localized: "player.mini_open_inbox"))
        case .listenAgain:
            return (.pill(String(localized: "player.mini_listen_again_ios"), prominent: false),
                    String(format: String(localized: "player.mini_listen_again_a11y"), snapshot.bookTitle), .shared)
        case .close: return (.close, String(localized: "player.close_book"), .shared)
        }
    }

    private static func labelled(_ text: String) -> (Primary, String, PrimaryAction) {
        (.pill(text, prominent: true), text, .shared)
    }

    /// The line in words. A chapter is named by the book's own title, never by our counter (Simon, 2026-10-14).
    static func lineText(_ line: MiniLine, chapterRemainingMs: Int64) -> String {
        func chapter(_ number: Int?, _ title: String?) -> String {
            if let title { return title }
            if let number { return String(format: String(localized: "player.mini_chapter"), Int32(number)) }
            return String(localized: "player.mini_whole_book")
        }
        func trailing(_ sleepMs: Int64?) -> String {
            if let sleepMs { return String(format: String(localized: "player.mini_sleep"), shortDuration(ms: sleepMs)) }
            return String(format: String(localized: "book.time_left"), shortDuration(ms: chapterRemainingMs))
        }
        switch line {
        case .chapter(let number, let title, let sleep):
            return [chapter(number, title), trailing(sleep)].joined(separator: " · ")
        case .paused(let number, let title, let sleep):
            return [String(localized: "player.mini_paused"), chapter(number, title), trailing(sleep)]
                .joined(separator: " · ")
        case .casting(let device, let number, let title):
            return [String(format: String(localized: "player.mini_playing_on"), device), chapter(number, title)]
                .joined(separator: " · ")
        case .starting: return String(localized: "player.mini_starting")
        case .loading: return String(localized: "player.mini_loading")
        case .reconnecting: return String(localized: "player.reconnecting")
        case .fadingOut: return String(localized: "player.mini_fading_out")
        case .finished: return String(localized: "player.mini_finished")
        case .problem(_, let title, _): return title
        }
    }

    /// "8 mins", "1 hr, 5 mins": the system's abbreviated units, floored, so a minute is never promised early. Under a
    /// minute the seconds are spelled out ("42 seconds"), because the system's abbreviation ("42 secs.") ends in a
    /// period that would sit mid-line before "left".
    static func shortDuration(ms: Int64) -> String {
        let clamped = max(0, ms)
        if clamped < 60_000 {
            return Duration.seconds(clamped / 1_000).formatted(.units(allowed: [.seconds], width: .wide))
        }
        return Duration.milliseconds(clamped).formatted(
            .units(allowed: [.hours, .minutes], width: .abbreviated, fractionalPart: .hide(rounded: .down)))
    }
}
