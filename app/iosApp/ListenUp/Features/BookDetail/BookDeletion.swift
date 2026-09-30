import Foundation

/// What Delete Book's confirmation says, and what it counts.
///
/// The copy is Android's `DeleteBookDialog`, string for string (`book.detail_delete_book_*`), for the
/// same reasons: it names the book's **folder** rather than "the library", counts the files ListenUp
/// *tracks* there (audio plus documents — a real library has folders carrying bonus PDFs the app never
/// modelled, so a bare "5 files" could be checked against the folder and found wrong), and says every
/// device loses the book. Understating what a permanent delete removes is the one thing it cannot do.
enum BookDeletion {
    /// The files ListenUp knows are in the book's folder, and their combined size.
    struct Tracked: Equatable {
        let fileCount: Int
        let bytes: Int64

        init(audioFileSizes: [Int64], documentSizes: [Int64]) {
            fileCount = audioFileSizes.count + documentSizes.count
            bytes = audioFileSizes.reduce(0, +) + documentSizes.reduce(0, +)
        }
    }

    static func title(bookTitle: String) -> String {
        String(format: String(localized: "book.detail_delete_book_title"), bookTitle)
    }

    /// `formattedSize` arrives formatted so the sentence stays testable; `ByteCountFormatter`'s
    /// output depends on the device's locale.
    static func message(fileCount: Int, formattedSize: String) -> String {
        String(
            format: String(localized: "book.detail_delete_book_body"),
            Int32(clamping: fileCount),
            formattedSize
        )
    }
}
