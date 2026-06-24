import Foundation

/// A native, value-typed projection of one Book-Edit relational chip (a contributor, series,
/// genre, tag, or mood).
///
/// **Why this exists (the SKIE no-bridged-`ForEach` convention).** The Book-Edit form's relations
/// arrive as SKIE-bridged Kotlin `Editable*` types. Feeding those straight into a `ForEach` makes
/// every SwiftUI diff re-read their properties across the Kotlin boundary — the same main-thread
/// re-bridging hazard that froze the library grid. `BookEditObserver` maps them to `EditableRelation`
/// once at the observer boundary; the chips diff cheap Swift values. The `id` is the same key the
/// view used to key on (contributor/series → name; genre/tag/mood → entity id) and is what the
/// observer uses to look the Kotlin object back up when a chip's remove button is tapped.
struct EditableRelation: Identifiable, Equatable, Hashable {
    /// The remove key — contributor/series key by name, genre/tag/mood by entity id.
    let id: String
    /// The precomputed chip label (e.g. series "Name · 1", tag slug → "Found Family").
    let label: String
}

// MARK: - Per-relation projections (pure, unit-tested)

extension EditableRelation {
    /// Contributor (author/narrator): keyed by name — the key the chips used to use (`id: \.name`).
    static func contributor(name: String) -> EditableRelation {
        EditableRelation(id: name, label: name)
    }

    /// Series: keyed by name; the label folds in the sequence ("Name · 1").
    static func series(name: String, sequence: String?) -> EditableRelation {
        EditableRelation(id: name, label: BookEditFormatting.seriesLabel(name: name, sequence: sequence))
    }

    /// Genre: keyed by entity id; the label is the name.
    static func genre(id: String, name: String) -> EditableRelation {
        EditableRelation(id: id, label: name)
    }

    /// Tag: keyed by entity id; the label title-cases the slug.
    static func tag(id: String, slug: String) -> EditableRelation {
        EditableRelation(id: id, label: BookEditFormatting.tagLabel(slug: slug))
    }

    /// Mood: keyed by entity id; the label title-cases the slug (same shape as a tag).
    static func mood(id: String, slug: String) -> EditableRelation {
        EditableRelation(id: id, label: BookEditFormatting.tagLabel(slug: slug))
    }
}
