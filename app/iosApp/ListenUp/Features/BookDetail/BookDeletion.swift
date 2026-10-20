import Foundation

/// What Delete from Server's alert says, and what it counts (spec §2.8, the iOS Confirms board's words).
///
/// The title names the book and the server; the message counts the files ListenUp *tracks* in its folder (audio plus
/// documents) and what they weigh, says everything else in its folder goes too, for every member, and that it can't be
/// undone. Understating what a permanent delete removes is the one thing it cannot do.
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
        String(format: String(localized: "book.detail_delete_server_title_ios"), bookTitle)
    }

    /// `formattedSize` arrives formatted so the sentence stays testable; `ByteCountFormatter`'s
    /// output depends on the device's locale.
    static func message(fileCount: Int, formattedSize: String) -> String {
        fileCount == 1
            ? String(format: String(localized: "book.detail_delete_server_body_one_ios"), formattedSize)
            : String(
                format: String(localized: "book.detail_delete_server_body_ios"),
                Int32(clamping: fileCount),
                formattedSize
            )
    }

    /// The alert's destructive verb.
    static var confirm: String { String(localized: "book.detail_delete_server_confirm_ios") }
}
