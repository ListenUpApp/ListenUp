import Foundation
@preconcurrency import Shared

/// The Library's one status strip as a Swift value (spec §3.1.1 L27/L28), mapped from the shared
/// `LibraryStatusStrip` decision: a scan, or a library still arriving. nil is the hidden slot.
enum LibraryStatusStripModel: Equatable {
    /// The server is scanning: `done` of `total` books, `added` of them new. `phase` names the work while
    /// the scan knows no book total yet.
    case scanning(done: Int, total: Int, added: Int, phase: String)
    /// The first population, or this device's first catch-up, is still arriving; `booksSoFar` is what the
    /// device holds now. The client never knows the server's total during a sync pull, so there is no "of N".
    case arriving(booksSoFar: Int)

    static func from(_ strip: LibraryStatusStrip) -> LibraryStatusStripModel? {
        switch strip.sealedType() {
        case .hidden:
            return nil
        case .scanning(let scanning):
            let scan = scanning.value.progress
            return .scanning(
                done: Int(scan.books),
                total: Int(scan.booksTotal),
                added: Int(scan.added),
                phase: scan.phaseDisplayName
            )
        case .arriving(let arriving):
            return .arriving(booksSoFar: Int(arriving.value.booksSoFar))
        }
    }

    /// "Scanning Your Server" or "Building Your Library" (HIG title case).
    var title: String {
        switch self {
        case .scanning: String(localized: "library.scan_strip_title_ios")
        case .arriving: String(localized: "library.arriving_title_ios")
        }
    }

    /// "412 of 1,280 · 3 new", or "412 books so far. Books appear here as they arrive; …".
    var detail: String {
        switch self {
        case .scanning(let done, let total, let added, let phase):
            guard total > 0 else { return phase }
            return added > 0
                ? String(
                    format: String(localized: "library.scan_strip_progress_new"),
                    done.formatted(), total.formatted(), added
                )
                : String(format: String(localized: "library.scan_strip_progress"), done.formatted(), total.formatted())
        case .arriving(let booksSoFar):
            switch booksSoFar {
            case 0: return String(localized: "library.arriving_none_yet")
            case 1: return String(localized: "library.arriving_so_far_one")
            default: return String(format: String(localized: "library.arriving_so_far"), booksSoFar.formatted())
            }
        }
    }

    /// How far a scan is, when it knows; nil draws an indeterminate bar.
    var fraction: Double? {
        guard case .scanning(let done, let total, _, _) = self, total > 0 else { return nil }
        return min(1, Double(done) / Double(total))
    }
}
