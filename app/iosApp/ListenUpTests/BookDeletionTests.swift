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

/// The two confirms' copy: Delete from Server and Remove Download (spec §2.8).
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

    @Test func theTitleNamesTheBookAndTheServer() {
        #expect(BookDeletion.title(bookTitle: "The Way of Kings") == "Delete “The Way of Kings” from the server?")
    }

    @Test func theMessageSaysTheFilesTheFolderEveryMemberAndNoUndo() {
        #expect(BookDeletion.message(fileCount: 76, formattedSize: "1.2 GB") ==
            "76 files (1.2 GB) and everything else in its folder are removed for every member. This can’t be undone.")
        #expect(BookDeletion.message(fileCount: 1, formattedSize: "1 KB") ==
            "1 file (1 KB) and everything else in its folder are removed for every member. This can’t be undone.")
    }

    @Test func theVerbIsDeleteBook() {
        #expect(BookDeletion.confirm == "Delete Book")
    }

    @Test func removeDownloadSaysWhatItFreesAndKeepsYourPlace() {
        #expect(RemoveDownloadCopy.title == "Remove Download?")
        #expect(RemoveDownloadCopy.message(formattedSize: "1.2 GB") ==
            "Frees 1.2 GB on this device. The book stays in your library and keeps your place.")
        #expect(RemoveDownloadCopy.keep == "Keep")
        #expect(RemoveDownloadCopy.remove == "Remove")
    }
}
