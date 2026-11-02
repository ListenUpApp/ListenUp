import Foundation
import ListenupContract
import Shared

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
        String(localized: .bookDetailDeleteServerTitleIos(bookTitle))
    }

    /// `formattedSize` arrives formatted so the sentence stays testable; `ByteCountFormatter`'s
    /// output depends on the device's locale.
    static func message(fileCount: Int, formattedSize: String) -> String {
        fileCount == 1
            ? String(localized: .bookDetailDeleteServerBodyOne(formattedSize))
            : String(
                format: String(localized: "book.detail_delete_server_body"),
                Int32(clamping: fileCount),
                formattedSize
            )
    }

    /// Why a delete was refused, in words the admin can act on: for a folder another book shares, which book is in
    /// the way (as Android and web say it); for anything else, the error's own message.
    static func refusal(for error: any AppError) -> String {
        guard let shared = error as? BookErrorFolderNotExclusive else { return error.message }
        return String(localized: .bookDetailDeleteBookBlocked(shared.otherBookTitle))
    }

    /// Whether the refusal is the shared-folder one, which Book Detail's own alert presents.
    static func isFolderRefusal(_ error: any AppError) -> Bool {
        error is BookErrorFolderNotExclusive
    }

    /// The alert's destructive verb.
    static var confirm: String { String(localized: "book.detail_delete_server_confirm_ios") }
}
