import Foundation
import Shared

// MARK: - Search phase

/// Flattened state of the search step: the result list and its load status.
enum MetadataSearchStatus: Equatable {
    case idle
    case inFlight
    case loaded([MetadataResultItem])
    case failed(String)
}

/// One Audible search hit, flattened for the result list.
struct MetadataResultItem: Identifiable, Equatable {
    /// ASIN doubles as the stable list id.
    let id: String
    let title: String
    let authors: String
    let narrators: String
    let coverURL: String?
    let runtimeMinutes: Int?
    let chapterCountText: String?

    var subtitleLine: String {
        [authors, narrators].filter { !$0.isEmpty }.joined(separator: " · ")
    }
}

// MARK: - Preview phase

/// Flattened state of the select-metadata step.
enum MetadataPreviewStatus: Equatable {
    case loading
    case ready(MetadataPreview)
    case failed(String)
}

/// The matched edition plus every per-field selection, ready for the select screen.
struct MetadataPreview: Equatable {
    let asin: String
    let title: String
    let authorsLine: String
    let narratorsLine: String
    let runtimeMinutes: Int?
    let coverURL: String?

    /// Simple scalar fields the user toggles as a unit, in display order. Empty entries
    /// (no value on the match) are already filtered out.
    let identityFields: [MetadataFieldSelection]
    let detailFields: [MetadataFieldSelection]

    let authors: [MetadataContributorSelection]
    let narrators: [MetadataContributorSelection]
    let seriesItems: [MetadataSeriesSelection]
    let genres: [MetadataGenreSelection]
    let moods: [MetadataGenreSelection]
    let tags: [MetadataGenreSelection]

    let descriptionField: MetadataFieldSelection?

    /// Cover options (Audible / iTunes HD) plus the implicit "keep current" choice.
    let coverEnabled: Bool
    let coverValueText: String
    /// The provider the probed cover came from (e.g. "iTunes", "Audible"), when known.
    let coverSourceLabel: String?
    /// The probed cover's pixel dimensions as "W×H" (U+00D7), when both are known.
    let coverResolution: String?

    /// Every provider that contributed a winning field to this match, for the "Merged from …"
    /// footer. One entry means a single source (footer hidden); more than one means a blend.
    let contributingSources: [String]

    let chapters: ChapterReviewState

    let isApplying: Bool
    let applyError: String?
    let previewNotFound: Bool

    /// Count of selected fields and the total selectable — drives the "N of M selected" header.
    let selectedCount: Int
    let totalCount: Int
}

/// A toggleable scalar metadata field (cover excluded — it has its own row). `sourceLabel` is the
/// provider that supplied this field's value when it fell back off the primary source, for a
/// per-field provenance chip.
struct MetadataFieldSelection: Identifiable, Equatable {
    let field: MetadataField
    let label: String
    let value: String
    let isSelected: Bool
    let systemImage: String
    let sourceLabel: String?

    var id: Int { Int(field.rawValue) }
}

/// One author/narrator with its own opt-in. `asin` is the toggle key (name fallback when Audible
/// omits the ASIN, matching the apply contract). `sourceLabel` is the field-level provenance
/// (shared across every contributor of the same role).
struct MetadataContributorSelection: Identifiable, Equatable {
    let id: String
    let name: String
    let isSelected: Bool
    let sourceLabel: String?
}

/// One series entry with its own opt-in. `sourceLabel` is the field-level provenance.
struct MetadataSeriesSelection: Identifiable, Equatable {
    let id: String
    let displayText: String
    let isSelected: Bool
    let sourceLabel: String?
}

/// One genre label with its own opt-in. `sourceLabel` is the field-level provenance (shared across
/// every label in the group).
struct MetadataGenreSelection: Identifiable, Equatable {
    let id: String
    let label: String
    let isSelected: Bool
    let sourceLabel: String?
}

// MARK: - Chapters

/// Flattened chapter-suggestion state.
enum ChapterReviewState: Equatable {
    /// No suggestion — hide the chapters section.
    case unavailable
    /// A different edition — show a disabled reason.
    case mismatch(localCount: Int, audibleCount: Int)
    /// Counts match — the review sheet is available.
    case available(AvailableChapters)
}

/// The reviewable chapter set: rows, selection, and apply status.
struct AvailableChapters: Equatable {
    let rows: [ChapterRenameRow]
    let selectedCount: Int
    let totalCount: Int
    let isApplying: Bool
    let applyError: String?

    var allSelected: Bool { selectedCount == totalCount }
}

/// One chapter's generic → named replacement, with its own opt-in.
struct ChapterRenameRow: Identifiable, Equatable {
    let ordinal: Int
    let currentName: String
    let suggestedName: String
    let isSelected: Bool

    var id: Int { ordinal }
}
