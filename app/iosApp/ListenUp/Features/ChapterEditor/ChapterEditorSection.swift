import Foundation

/// The two levels a marker can open, as the views speak of them. The observer is the only place that
/// turns this into Kotlin's `ChapterMarkerTier`.
enum MarkerTier: Equatable {
    case part
    case book
}

/// A marker the list shows, flattened from the shared outline.
struct EditorMarker: Equatable {
    /// The chapter carrying the marker, which is also where it starts.
    let chapterId: String
    let tier: MarkerTier
    let title: String
}

/// One line of the shared outline as a native value, so sectioning needs no bridged object.
enum ChapterOutlineEntry: Equatable {
    case marker(EditorMarker)
    case chapter(EditableChapterRow)
}

/// A run of chapters under the markers that open it — a `Section` in the editor's list, because that
/// is the HIG's shape for a grouped list.
///
/// `markers` holds more than one only when a single chapter opens both tiers, Book before Part. It is
/// empty for chapters before the first marker, and for every chapter of a book with no markers: one
/// section, drawn exactly as the list was before markers existed.
struct ChapterEditorSection: Identifiable, Equatable {
    var markers: [EditorMarker] = []
    var rows: [EditableChapterRow] = []

    var id: String { markers.first?.chapterId ?? rows.first?.id ?? "" }

    /// Cuts the outline into sections. The grouping itself is the outline's: this only starts a new
    /// section at each marker, and keeps a book and part opened by the same chapter together.
    static func sections(from entries: [ChapterOutlineEntry]) -> [ChapterEditorSection] {
        var sections: [ChapterEditorSection] = []
        var current = ChapterEditorSection()
        for entry in entries {
            switch entry {
            case .marker(let marker):
                if !current.rows.isEmpty {
                    sections.append(current)
                    current = ChapterEditorSection()
                }
                current.markers.append(marker)
            case .chapter(let row):
                current.rows.append(row)
            }
        }
        if !current.rows.isEmpty || !current.markers.isEmpty { sections.append(current) }
        return sections
    }
}

/// The marker sheet's subject.
struct MarkerEdit: Identifiable, Equatable {
    /// The chapter carrying the marker, or about to.
    let chapterId: String
    let tier: MarkerTier
    /// What the field opens on: the marker's name, or a default for a new one.
    let name: String
    /// Nothing is written until Save, so there is nothing yet to move or remove.
    let isNew: Bool

    var id: String { "\(chapterId)-\(tier)" }
}

extension EditableChapterRow {
    /// Whether this chapter already opens `tier`, which is what rules it out as a place to move to.
    func opens(_ tier: MarkerTier) -> Bool {
        switch tier {
        case .part: partTitle != nil
        case .book: bookTitle != nil
        }
    }
}
