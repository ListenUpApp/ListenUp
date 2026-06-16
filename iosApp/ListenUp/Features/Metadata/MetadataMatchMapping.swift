import Foundation
@preconcurrency import Shared

/// Pure transforms from the Kotlin `MetadataViewModel` sealed states into the flattened Swift
/// view models. Kept free of `@Observable`/actor state so every branch is unit-testable in
/// isolation (see `MetadataMatchMappingTests`).
enum MetadataMatchMapping {
    // MARK: - Search

    static func searchPhase(from state: MetadataUiStateSearch) -> MetadataSearchStatus {
        switch onEnum(of: state.loadState) {
        case .idle:
            return .idle
        case .inFlight:
            return .inFlight
        case .loaded(let loaded):
            return .loaded(loaded.results.map(resultItem(from:)))
        case .failed(let failed):
            return .failed(failed.message)
        }
    }

    static func resultItem(from book: MetadataBook) -> MetadataResultItem {
        MetadataResultItem(
            id: book.asin,
            title: book.title,
            authors: joinedNames(book.authors),
            narrators: joinedNames(book.narrators),
            coverURL: book.coverUrl,
            runtimeMinutes: book.runtimeMinutes?.intValue,
            chapterCountText: nil
        )
    }

    // MARK: - Preview

    static func previewPhase(from state: MetadataUiStatePreview) -> MetadataPreviewStatus {
        switch onEnum(of: state.loadState) {
        case .loading:
            return .loading
        case .failed(let failed):
            return .failed(failed.message)
        case .ready(let ready):
            return .ready(preview(from: ready, match: state.match))
        }
    }

    static func preview(from ready: PreviewLoadStateReady, match: MetadataBook) -> MetadataPreview {
        let book = ready.preview
        let sel = ready.selections

        let identity = identityFields(book: book, selections: sel)
        let details = detailFields(book: book, selections: sel)
        let authors = contributors(book.authors, selected: sel.selectedAuthors)
        let narrators = contributors(book.narrators, selected: sel.selectedNarrators)
        let seriesItems = series(book.series, selected: sel.selectedSeries)
        let genres = genreSelections(book.genres, selected: sel.selectedGenres)
        let moods = genreSelections(book.moods, selected: sel.selectedMoods)
        let tags = genreSelections(book.tags, selected: sel.selectedTags)
        let description = descriptionField(book: book, selections: sel)

        let scalarFields = identity + details + (description.map { [$0] } ?? [])
        let counts = selectionCounts(
            scalarFields: scalarFields,
            groups: [authors.map(\.isSelected), narrators.map(\.isSelected),
                     seriesItems.map(\.isSelected), genres.map(\.isSelected),
                     moods.map(\.isSelected), tags.map(\.isSelected)],
            coverEnabled: sel.cover
        )

        return MetadataPreview(
            asin: match.asin,
            title: book.title,
            authorsLine: joinedNames(book.authors),
            narratorsLine: joinedNames(book.narrators),
            runtimeMinutes: book.runtimeMinutes?.intValue,
            coverURL: ready.selectedCoverUrl ?? book.coverUrl,
            identityFields: identity,
            detailFields: details,
            authors: authors,
            narrators: narrators,
            seriesItems: seriesItems,
            genres: genres,
            moods: moods,
            tags: tags,
            descriptionField: description,
            coverEnabled: sel.cover,
            coverValueText: String(localized: "metadata.field_cover_value"),
            chapters: chapterState(from: ready.chapterSuggestion),
            isApplying: ready.isApplying,
            applyError: ready.applyError,
            previewNotFound: ready.previewNotFound,
            selectedCount: counts.selected,
            totalCount: counts.total
        )
    }

    // MARK: - Field builders

    private static func identityFields(
        book: MetadataBook,
        selections: MetadataSelections
    ) -> [MetadataFieldSelection] {
        var out: [MetadataFieldSelection] = []
        if !book.title.isEmpty {
            out.append(.init(
                field: .title, label: String(localized: "metadata.field_title"),
                value: book.title, isSelected: selections.title, systemImage: "textformat"
            ))
        }
        if let subtitle = book.subtitle, !subtitle.isEmpty {
            out.append(.init(
                field: .subtitle, label: String(localized: "metadata.field_subtitle"),
                value: subtitle, isSelected: selections.subtitle, systemImage: "text.alignleft"
            ))
        }
        return out
    }

    private static func detailFields(
        book: MetadataBook,
        selections: MetadataSelections
    ) -> [MetadataFieldSelection] {
        var out: [MetadataFieldSelection] = []
        if let publisher = book.publisher, !publisher.isEmpty {
            out.append(.init(
                field: .publisher, label: String(localized: "metadata.field_publisher"),
                value: publisher, isSelected: selections.publisher, systemImage: "building.2"
            ))
        }
        if let releaseDate = book.releaseDate, !releaseDate.isEmpty {
            out.append(.init(
                field: .releaseDate, label: String(localized: "metadata.field_release_date"),
                value: releaseDate, isSelected: selections.releaseDate, systemImage: "calendar"
            ))
        }
        if let language = book.language, !language.isEmpty {
            out.append(.init(
                field: .language, label: String(localized: "metadata.field_language"),
                value: language, isSelected: selections.language, systemImage: "globe"
            ))
        }
        return out
    }

    private static func descriptionField(
        book: MetadataBook,
        selections: MetadataSelections
    ) -> MetadataFieldSelection? {
        guard let description = book.description_, !description.isEmpty else { return nil }
        return .init(
            field: .theDescription, label: String(localized: "metadata.field_description"),
            value: description, isSelected: selections.description_, systemImage: "doc.text"
        )
    }

    private static func contributors(
        _ refs: [MetadataContributorRef],
        selected: Set<String>
    ) -> [MetadataContributorSelection] {
        refs.map { ref in
            let key = ref.asin ?? ref.name
            return MetadataContributorSelection(id: key, name: ref.name, isSelected: selected.contains(key))
        }
    }

    private static func series(
        _ refs: [MetadataSeriesRef],
        selected: Set<String>
    ) -> [MetadataSeriesSelection] {
        refs.map { ref in
            let key = ref.asin ?? ref.title
            let display = ref.sequence.map { "\(ref.title) #\($0)" } ?? ref.title
            return MetadataSeriesSelection(id: key, displayText: display, isSelected: selected.contains(key))
        }
    }

    private static func genreSelections(
        _ genres: [String],
        selected: Set<String>
    ) -> [MetadataGenreSelection] {
        genres.map { MetadataGenreSelection(id: $0, label: $0, isSelected: selected.contains($0)) }
    }

    // MARK: - Chapters

    static func chapterState(from suggestion: ChapterSuggestion) -> ChapterReviewState {
        switch onEnum(of: suggestion) {
        case .unavailable:
            return .unavailable
        case .countMismatch(let mismatch):
            return .mismatch(localCount: Int(mismatch.localCount), audibleCount: Int(mismatch.audibleCount))
        case .available(let available):
            let selected = available.selectedOrdinals
            let rows = available.rows.map { row in
                ChapterRenameRow(
                    ordinal: Int(row.ordinal),
                    currentName: row.currentName,
                    suggestedName: row.suggestedName,
                    isSelected: selected.contains(KotlinInt(int: row.ordinal))
                )
            }
            return .available(AvailableChapters(
                rows: rows,
                selectedCount: selected.count,
                totalCount: rows.count,
                isApplying: available.isApplying,
                applyError: available.applyError
            ))
        }
    }

    // MARK: - Selection counts

    private struct Counts { let selected: Int; let total: Int }

    /// Counts selectable lines and how many are on. Each scalar field is one line; each relational
    /// `group` (the per-entry `isSelected` flags) is one line that counts as selected when any entry
    /// is on, and is skipped entirely when empty — mirroring the apply contract (a non-empty set
    /// replaces, an empty set leaves the relation untouched). The cover is always one line.
    private static func selectionCounts(
        scalarFields: [MetadataFieldSelection],
        groups: [[Bool]],
        coverEnabled: Bool
    ) -> Counts {
        var total = 1 // cover row
        var selected = coverEnabled ? 1 : 0
        for field in scalarFields {
            total += 1
            if field.isSelected { selected += 1 }
        }
        for group in groups where !group.isEmpty {
            total += 1
            if group.contains(true) { selected += 1 }
        }
        return Counts(selected: selected, total: total)
    }

    // MARK: - Helpers

    static func joinedNames(_ refs: [MetadataContributorRef]) -> String {
        refs.map(\.name).joined(separator: ", ")
    }
}
