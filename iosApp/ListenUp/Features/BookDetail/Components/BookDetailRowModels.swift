import Foundation
import Shared

/// Native, value-typed projection of a chapter for the Book Detail chapters list.
///
/// `ChapterUiModel` is a Swift Export-bridged Kotlin type; feeding it straight into a `ForEach`
/// re-reads its properties across the Swift Export boundary on every diff. A long audiobook has
/// 100+ chapters, so this maps each to plain Swift values ONCE at the observer boundary.
struct BookChapterRow: Identifiable, Equatable, Hashable {
    let id: String
    let title: String
    let duration: String
    let isCurrent: Bool

    init(id: String, title: String, duration: String, isCurrent: Bool) {
        self.id = id
        self.title = title
        self.duration = duration
        self.isCurrent = isCurrent
    }

    init(_ chapter: ChapterUiModel) {
        self.id = chapter.id
        self.title = chapter.title
        self.duration = chapter.duration
        self.isCurrent = chapter.isCurrent
    }
}

/// Native, value-typed projection of a supplementary document for the Book Detail list.
/// Mirrors `BookChapterRow`'s rationale — keeps the bridged `BookDocument` out of the `ForEach`.
struct DocumentRow: Identifiable, Equatable, Hashable {
    let id: String
    let format: String
    let filename: String
    let size: Int64

    init(id: String, format: String, filename: String, size: Int64) {
        self.id = id
        self.format = format
        self.filename = filename
        self.size = size
    }

    init(_ document: BookDocument) {
        self.id = document.id
        self.format = document.format
        self.filename = document.filename
        self.size = document.size
    }
}
