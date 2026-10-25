import Foundation

/// One row in the CarPlay "Chapters" list (the now-playing template's Up Next screen).
///
/// A native value type, same rule as `CarPlayRow`: the list is rebuilt per render, and rows must
/// never hand bridged Kotlin objects to the template layer.
struct CarPlayChapterRow: Equatable, Identifiable {
    /// Chapter index — what selecting the row hands to `PlayerObserver.selectChapter(index:)`.
    let index: Int
    let title: String
    /// Marks the chapter currently playing, rendered as the row's playing indicator.
    let isCurrent: Bool

    var id: Int { index }
}

/// Builds the chapter rows for the current book.
enum CarPlayChapterRows {
    /// Maps chapter titles to rows, numbering untitled chapters the way the phone player does
    /// ("Chapter 1", 1-based) so the car never shows a blank row.
    static func chapters(titles: [String?], currentIndex: Int) -> [CarPlayChapterRow] {
        titles.enumerated().map { index, title in
            CarPlayChapterRow(
                index: index,
                title: title ?? String(format: String(localized: "player.chapter_number"), index + 1),
                isCurrent: index == currentIndex
            )
        }
    }
}

/// One of the car's custom now-playing buttons: its glyph, the name VoiceOver speaks for it, and what it does. The
/// template's image buttons have no title, so the label rides on the image (HIG, CarPlay; VoiceOver — every control needs
/// a label).
///
/// They move by the shared rule every surface uses (§3.3, `ChapterNavigation` through `PlayerObserver`): previous
/// restarts the chapter after 3 s and chapter 1 restarts, so it is enabled in every chaptered book and disabled only in a
/// chapterless one; next is disabled at the last chapter.
struct CarPlayChapterButton: Equatable {
    /// Which way a chapter button moves.
    enum Action: Equatable {
        case previous
        case next
    }

    let symbolName: String
    let accessibilityLabel: String
    let action: Action

    static let previous = CarPlayChapterButton(
        symbolName: "backward.end",
        accessibilityLabel: String(localized: "player.previous_chapter"),
        action: .previous
    )
    static let next = CarPlayChapterButton(
        symbolName: "forward.end",
        accessibilityLabel: String(localized: "player.next_chapter"),
        action: .next
    )

    /// In the order the template shows them.
    static let all: [CarPlayChapterButton] = [.previous, .next]

    /// Whether the button can do anything: previous wherever the book has chapters (it can restart one), next only
    /// before the last.
    func isEnabled(hasPreviousChapter: Bool, hasNextChapter: Bool) -> Bool {
        switch action {
        case .previous: hasPreviousChapter
        case .next: hasNextChapter
        }
    }
}
