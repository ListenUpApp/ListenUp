import Foundation
import Shared

/// Turns a refused chapter set into a sentence that points at a row.
enum ChapterProblemText {
    /// Says which chapter is wrong, in the number the reader can actually see.
    ///
    /// A refused save is only useful if it points somewhere. Problems carry a chapter *id*, which
    /// is meaningless on screen, so it is resolved to the row's position here.
    ///
    /// ⛔ Only the first problem is reported. One invalid boundary usually trips several rules at
    /// once — a negative start is both outside the book and out of order — and listing all of them
    /// describes the checker rather than the mistake.
    static func message(for problems: [ChapterSetProblem], chapters: [EditableChapterRow]) -> String? {
        guard let problem = problems.first else { return nil }
        guard let row = chapters.first(where: { $0.id == problem.chapterId }) else {
            return String(localized: "chapter_editor.problem_unknown_chapter")
        }

        // ⛔ `String(localized: "literal")` in each arm, not a `LocalizationValue` variable. The
        // `verifySwiftStringKeys` gate only sees statically-resolvable keys, and iOS renders a
        // missing key as its own text — a key it cannot see is a key that can ship as garbage.
        let format = switch problem.sealedType() {
        case .blankTitle: String(localized: "chapter_editor.problem_blank_title")
        case .titleTooLong: String(localized: "chapter_editor.problem_title_too_long")
        case .notStrictlyIncreasing: String(localized: "chapter_editor.problem_not_increasing")
        case .outsideBook: String(localized: "chapter_editor.problem_outside_book")
        }
        return String(format: format, row.number)
    }
}
