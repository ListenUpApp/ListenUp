import Foundation

/// The display basename of a book-root-relative document path ("extras/map.pdf" → "map.pdf").
func documentBasename(_ filename: String) -> String {
    (filename as NSString).lastPathComponent
}

/// A compact, deterministic human-readable size. KB below 1 MB (rounded up so a non-empty
/// file never reads "0 KB"), one decimal MB/GB above. Deterministic for tests — not
/// `ByteCountFormatter`, whose output is locale/OS-dependent.
func formatDocumentSize(_ bytes: Int64) -> String {
    if bytes <= 0 { return "0 KB" }
    let kb = 1024.0
    let mb = kb * 1024
    let gb = mb * 1024
    let b = Double(bytes)
    if b < mb {
        return "\(Int((b / kb).rounded(.up))) KB"
    } else if b < gb {
        return String(format: "%.1f MB", b / mb)
    } else {
        return String(format: "%.1f GB", b / gb)
    }
}

/// SF Symbol for a document format token (lowercased extension). Generic `doc` fallback.
func documentFormatSymbol(_ format: String) -> String {
    switch format.lowercased() {
    case "pdf": return "doc.richtext"
    case "epub", "mobi", "azw3": return "book.closed"
    case "cbr", "cbz": return "photo.stack"
    default: return "doc"
    }
}

/// 1-based current page + total, clamped to a valid range. `pageCount == 0` → (0, 0).
struct PageDisplay: Equatable {
    let page: Int
    let total: Int
}

func pageDisplay(currentIndex: Int, pageCount: Int) -> PageDisplay {
    guard pageCount > 0 else { return PageDisplay(page: 0, total: 0) }
    let clamped = min(max(currentIndex, 0), pageCount - 1)
    return PageDisplay(page: clamped + 1, total: pageCount)
}
