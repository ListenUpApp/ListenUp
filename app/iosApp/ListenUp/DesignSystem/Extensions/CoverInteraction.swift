import CoreTransferable
import SwiftUI

/// What a book cover carries when it is dragged out of a grid: the title and author as plain text,
/// so dropping it into Notes, Messages or Mail writes something a person can read.
///
/// HIG, Drag and drop: "Offer multiple representations of dragged content" and prefer a format the
/// destination understands — plain text is the one every text destination on the system accepts.
/// The book id stays in the app; an id pasted into Notes would mean nothing to anyone.
struct BookDragItem: Transferable, Equatable {
    let title: String
    let author: String

    /// "Title — Author", or the title alone when the author is unknown.
    var plainText: String {
        let trimmedAuthor = author.trimmingCharacters(in: .whitespacesAndNewlines)
        return trimmedAuthor.isEmpty ? title : "\(title) — \(trimmedAuthor)"
    }

    static var transferRepresentation: some TransferRepresentation {
        ProxyRepresentation(exporting: \.plainText)
    }
}

extension View {
    /// The pointer lift for a book, series or shelf cover.
    ///
    /// HIG, Pointing devices: "Use lift for a small element that has an opaque background" — a cover
    /// is exactly that. The hover shape follows the cover's rounded corners, so the lift and its
    /// specular highlight sit on the artwork rather than on the whole card.
    func coverHoverEffect(cornerRadius: CGFloat = 8) -> some View {
        contentShape(.hoverEffect, RoundedRectangle(cornerRadius: cornerRadius, style: .continuous))
            .hoverEffect(.lift)
    }

    /// Lets a cover be dragged to another app (or another window) as `BookDragItem`, previewed by
    /// the cover itself. HIG, Drag and drop: "Support drag and drop … especially in iPadOS".
    func bookCoverDraggable(title: String, author: String, @ViewBuilder preview: () -> some View) -> some View {
        draggable(BookDragItem(title: title, author: author), preview: preview)
    }
}
