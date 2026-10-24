import CoreGraphics
import SwiftUI

/// Whether the companion PDF reader puts the player beside the page.
///
/// One spec with Android's `readerLayout` (`features/documentviewer/ReaderLayout.kt`); change both together. Nothing
/// playing keeps the page alone. A phone half open like a book puts the page and the player on facing pages; half open
/// on a table keeps the page alone (a page reads badly on a half lying flat) with its now-playing strip. Otherwise a
/// window regular in both directions (a full-screen iPad) has room for the pane; anything narrower or shorter keeps the
/// page alone.
enum ReaderLayoutMode: Equatable {
    /// The page alone, the now-playing strip docked under it.
    case single
    /// The page and the listening pane side by side, divided where the split says.
    case beside(ReaderSplit)

    /// The listening pane's width beside the page in a wide window.
    static let paneWidth: CGFloat = 360

    static func resolve(
        horizontalSizeClass: UserInterfaceSizeClass?,
        verticalSizeClass: UserInterfaceSizeClass?,
        fold: PlayerFold,
        hasCurrentBook: Bool
    ) -> ReaderLayoutMode {
        guard hasCurrentBook else { return .single }
        switch fold {
        case .book(let hinge): return .beside(.hinge(hinge))
        case .tabletop: return .single
        case .none: break
        }
        return horizontalSizeClass == .regular && verticalSizeClass == .regular ? .beside(.trailingPane) : .single
    }
}

/// Where `ReaderLayoutMode.beside` divides the page from the listening pane.
enum ReaderSplit: Equatable {
    /// At the hinge of a phone half open like a book, in the reader's own coordinates.
    case hinge(CGRect)
    /// A pane `ReaderLayoutMode.paneWidth` wide against the trailing edge.
    case trailingPane
}
