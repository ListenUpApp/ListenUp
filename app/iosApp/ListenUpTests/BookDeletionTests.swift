import Foundation
import Testing
@testable import ListenUp
@preconcurrency import Shared

/// Delete Book on iOS: what the observer does with the ViewModel's one-shot events, and what the
/// confirmation says.
///
/// ⛔ `BookDetailObserver` used to log `BookDeleted` as unreachable, because iOS had no way to ask for
/// a delete. Now it can, and leaving the screen is the only honest answer: the book's row is about to
/// vanish when the tombstone syncs.
@Suite("BookDetailObserver.navReaction")
struct BookDetailNavReactionTests {
    @Test func aDeletedBookLeavesTheScreen() {
        #expect(BookDetailObserver.navReaction(to: BookDetailNavActionBookDeleted.shared) == .leaveDeletedBook)
    }

    @Test func aReadyDocumentOpensTheReader() {
        let action = BookDetailNavActionOpenDocumentViewer(localPath: "/tmp/map.pdf")
        #expect(BookDetailObserver.navReaction(to: action) == .openDocument(localPath: "/tmp/map.pdf"))
    }

    @Test func anUnsupportedDocumentSaysSo() {
        #expect(BookDetailObserver.navReaction(to: BookDetailNavActionShowViewerComingSoon.shared) == .showComingSoon)
    }
}

/// The confirmation's copy — Android's `DeleteBookDialog`, string for string.
@Suite("BookDeletion")
struct BookDeletionTests {
    @Test func tracksAudioAndDocumentsTogether() {
        // Documents live in the same folder and go with it, so they count.
        let tracked = BookDeletion.Tracked(audioFileSizes: [100, 200, 300], documentSizes: [50, 25])
        #expect(tracked.fileCount == 5)
        #expect(tracked.bytes == 675)
    }

    @Test func aBookWithNothingTrackedCountsZero() {
        let tracked = BookDeletion.Tracked(audioFileSizes: [], documentSizes: [])
        #expect(tracked.fileCount == 0)
        #expect(tracked.bytes == 0)
    }

    @Test func titleNamesTheBook() {
        #expect(BookDeletion.title(bookTitle: "The Institute") == "Delete “The Institute”?")
    }

    @Test func messageSaysTheFolderGoesWithACountAndASize() {
        let message = BookDeletion.message(fileCount: 5, formattedSize: "514 MB")
        #expect(message == "This permanently deletes the book’s folder from your server. ListenUp tracks 5 files "
            + "in it (514 MB), and everything else in that folder goes too — PDFs, artwork, bonus material — "
            + "whether ListenUp shows it or not. The book disappears from every device, and this can’t be undone.")
    }
}
