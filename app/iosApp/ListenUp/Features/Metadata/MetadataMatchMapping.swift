import Foundation
import Shared

/// Pure transforms from the Kotlin `MetadataViewModel` sealed states into the flattened Swift
/// view models. Kept free of `@Observable`/actor state so every branch is unit-testable in
/// isolation (see `MetadataMatchMappingTests`).
enum MetadataMatchMapping {
    // MARK: - Search

    static func searchPhase(from state: MetadataUiStateSearch) -> MetadataSearchStatus {
        switch state.loadState.sealedType() {
        case .idle:
            return .idle
        case .inFlight:
            return .inFlight
        case .loaded(let loadedType):
            let loaded = loadedType.value
            return .loaded(loaded.results.map(resultItem(from:)))
        case .failed(let failedType):
            let failed = failedType.value
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
            runtimeMinutes: book.runtimeMinutes.map { Int($0) },
            chapterCountText: nil
        )
    }

    // MARK: - Preview

    static func previewPhase(from state: MetadataUiStatePreview) -> MetadataPreviewStatus {
        switch state.loadState.sealedType() {
        case .loading:
            return .loading
        case .failed(let failedType):
            let failed = failedType.value
            return .failed(failed.message)
        case .ready(let readyType):
            let ready = readyType.value
            return .ready(preview(from: ready, match: state.match))
        }
    }

    /// Per-field provenance is resolved by a Kotlin accessor, never by subscripting the bridged
    /// `Map<BookField, String>`: Swift Export force-casts the map's opaque Kotlin enum keys to this
    /// *pure Swift* `BookField`, which has no Objective-C identity, so the cast traps as soon as the
    /// match carries any fallback provenance. Passing a `BookField` *into* Kotlin as an argument is
    /// the direction that bridges. Same trap that crashed search on `Set<SearchHitType>`; see
    /// `NoBridgedEnumCollectionsInUiStateRule`.
    static func preview(from ready: PreviewLoadStateReady, match: MetadataBook) -> MetadataPreview {
        preview(
            from: ready,
            match: match,
            sourceFor: { ready.fallbackSourceFor(field: $0) },
            genreSourceFor: { ready.genreSourceFor(label: $0) },
            moodSourceFor: { ready.moodSourceFor(label: $0) }
        )
    }

    /// Provenance-injecting seam. Production passes the Kotlin accessor above; tests pass a native
    /// closure, because a `[BookField: String]` built in Swift and handed to the Kotlin initializer
    /// is not the map production sees — its keys are Swift-boxed, so a Kotlin-side lookup misses.
    /// `genreSourceFor`/`moodSourceFor` name where each genre or mood came from (#1542); one that names
    /// nothing falls back to the field's own source.
    static func preview(
        from ready: PreviewLoadStateReady,
        match: MetadataBook,
        sourceFor: (BookField) -> String?,
        genreSourceFor: (String) -> String? = { _ in nil },
        moodSourceFor: (String) -> String? = { _ in nil }
    ) -> MetadataPreview {
        let book = ready.preview
        let sel = ready.selections

        let identity = identityFields(book: book, selections: sel, sourceFor: sourceFor)
        let details = detailFields(book: book, selections: sel, sourceFor: sourceFor)
        let authors = contributors(book.authors, selected: sel.selectedAuthors, sourceLabel: sourceFor(.authors))
        let narrators = contributors(
            book.narrators, selected: sel.selectedNarrators, sourceLabel: sourceFor(.narrators)
        )
        let seriesItems = series(book.series, selected: sel.selectedSeries, sourceLabel: sourceFor(.series))
        let genres = genreSelections(
            book.genres, selected: sel.selectedGenres, sourceFor: genreSourceFor, fieldSource: sourceFor(.genres)
        )
        let moods = genreSelections(
            book.moods, selected: sel.selectedMoods, sourceFor: moodSourceFor, fieldSource: sourceFor(.moods)
        )
        let tags = genreSelections(book.tags, selected: sel.selectedTags, fieldSource: sourceFor(.tags))
        let description = descriptionField(book: book, selections: sel, sourceLabel: sourceFor(.description))

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
            runtimeMinutes: book.runtimeMinutes.map { Int($0) },
            coverURL: book.coverUrl,
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
            // Map the shared cover candidates into native value types HERE (the boundary), so the
            // ForEach in the view never re-bridges a Kotlin list per diff (per iosApp/CLAUDE.md).
            coverOptions: ready.coverEntries.map {
                MetadataCoverOption(url: $0.url, label: $0.label, resolution: $0.resolution)
            },
            // The shared ViewModel decides which cover Apply writes; read it, never re-derive it.
            appliedCoverURL: ready.appliedCover?.url,
            appliedCoverLabel: ready.appliedCover?.label,
            // Copy into a native array at the boundary — never hold the bridged Kotlin list.
            contributingSources: Array(ready.contributingSources),
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
        selections: MetadataSelections,
        sourceFor: (BookField) -> String?
    ) -> [MetadataFieldSelection] {
        var out: [MetadataFieldSelection] = []
        if !book.title.isEmpty {
            out.append(.init(
                field: .title, label: String(localized: "metadata.field_title"),
                value: book.title, isSelected: selections.title, systemImage: "textformat",
                sourceLabel: sourceFor(.title)
            ))
        }
        if let subtitle = book.subtitle, !subtitle.isEmpty {
            out.append(.init(
                field: .subtitle, label: String(localized: "metadata.field_subtitle"),
                value: subtitle, isSelected: selections.subtitle, systemImage: "text.alignleft",
                sourceLabel: sourceFor(.subtitle)
            ))
        }
        return out
    }

    private static func detailFields(
        book: MetadataBook,
        selections: MetadataSelections,
        sourceFor: (BookField) -> String?
    ) -> [MetadataFieldSelection] {
        var out: [MetadataFieldSelection] = []
        if let publisher = book.publisher, !publisher.isEmpty {
            out.append(.init(
                field: .publisher, label: String(localized: "metadata.field_publisher"),
                value: publisher, isSelected: selections.publisher, systemImage: "building.2",
                sourceLabel: sourceFor(.publisher)
            ))
        }
        if let releaseDate = book.releaseDate, !releaseDate.isEmpty {
            out.append(.init(
                field: .releaseDate, label: String(localized: "metadata.field_release_date"),
                value: releaseDate, isSelected: selections.releaseDate, systemImage: "calendar",
                // Release date is the `PUBLISH_YEAR` field in the provenance router.
                sourceLabel: sourceFor(.publishYear)
            ))
        }
        if let language = book.language, !language.isEmpty {
            out.append(.init(
                field: .language, label: String(localized: "metadata.field_language"),
                value: language, isSelected: selections.language, systemImage: "globe",
                sourceLabel: sourceFor(.language)
            ))
        }
        return out
    }

    private static func descriptionField(
        book: MetadataBook,
        selections: MetadataSelections,
        sourceLabel: String?
    ) -> MetadataFieldSelection? {
        guard let description = book.descriptionText, !description.isEmpty else { return nil }
        return .init(
            field: .description, label: String(localized: "metadata.field_description"),
            value: description, isSelected: selections.descriptionSelected, systemImage: "doc.text",
            sourceLabel: sourceLabel
        )
    }

    private static func contributors(
        _ refs: [MetadataContributorRef],
        selected: Set<String>,
        sourceLabel: String?
    ) -> [MetadataContributorSelection] {
        refs.map { ref in
            let key = ref.asin ?? ref.name
            return MetadataContributorSelection(
                id: key, name: ref.name, isSelected: selected.contains(key), sourceLabel: sourceLabel
            )
        }
    }

    private static func series(
        _ refs: [MetadataSeriesRef],
        selected: Set<String>,
        sourceLabel: String?
    ) -> [MetadataSeriesSelection] {
        refs.map { ref in
            let key = ref.asin ?? ref.title
            let display = ref.sequence.map { "\(ref.title) #\($0)" } ?? ref.title
            return MetadataSeriesSelection(
                id: key, displayText: display, isSelected: selected.contains(key), sourceLabel: sourceLabel
            )
        }
    }

    /// Each value carries its own source (#1542): one Hardcover added beside the match's own says so, and
    /// a value its lookup names nothing for falls back to the field's own source.
    private static func genreSelections(
        _ genres: [String],
        selected: Set<String>,
        sourceFor: (String) -> String? = { _ in nil },
        fieldSource: String?
    ) -> [MetadataGenreSelection] {
        genres.map {
            MetadataGenreSelection(
                id: $0, label: $0, isSelected: selected.contains($0), sourceLabel: sourceFor($0) ?? fieldSource
            )
        }
    }

    /// Genres or moods grouped by where they came from, in first-seen order: the match's own, then each
    /// source's additions, so each group shows its source chip once (#1542).
    nonisolated static func sourceRuns(_ values: [MetadataGenreSelection]) -> [MetadataSourceRun] {
        var order: [String?] = []
        var bySource: [String?: [MetadataGenreSelection]] = [:]
        for value in values {
            if bySource[value.sourceLabel] == nil { order.append(value.sourceLabel) }
            bySource[value.sourceLabel, default: []].append(value)
        }
        return order.map { MetadataSourceRun(source: $0, items: bySource[$0] ?? []) }
    }

    // MARK: - Chapters

    static func chapterState(from suggestion: ChapterSuggestion) -> ChapterReviewState {
        switch suggestion.sealedType() {
        case .unavailable:
            return .unavailable
        case .countMismatch(let mismatchType):
            let mismatch = mismatchType.value
            return .mismatch(localCount: Int(mismatch.localCount), audibleCount: Int(mismatch.audibleCount))
        case .available(let availableType):
            let available = availableType.value
            let selected = available.selectedOrdinals
            let rows = available.rows.map { row in
                ChapterRenameRow(
                    ordinal: Int(row.ordinal),
                    currentName: row.currentName,
                    suggestedName: row.suggestedName,
                    isSelected: selected.contains(row.ordinal)
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

    // MARK: - After apply

    /// The confirmation's receipt: the cover's real source (nil = kept) and only the chapter names
    /// that were actually written, never the pre-ticked selection.
    static func receipt(from preview: MetadataPreview, chaptersNamed: Int) -> MetadataApplyReceipt {
        MetadataApplyReceipt(
            fieldsApplied: preview.selectedCount,
            coverSource: preview.appliedCoverLabel,
            chaptersNamed: chaptersNamed
        )
    }

    /// An apply error to raise, once: only when one has just appeared (the ViewModel clears it while
    /// a retry is in flight, so a repeated failure appears again).
    static func surfacedApplyError(previous: String?, current: String?) -> String? {
        guard let current, previous == nil else { return nil }
        return current
    }

    /// How many chapter names are ticked in [phase], or zero when chapter names are not on offer.
    static func selectedChapterCount(_ status: MetadataPreviewStatus?) -> Int {
        guard case .ready(let preview) = status, case .available(let available) = preview.chapters else { return 0 }
        return available.selectedCount
    }

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

/// One group of genres or moods from one source, for the preview's chip rows.
struct MetadataSourceRun: Identifiable, Equatable {
    let source: String?
    let items: [MetadataGenreSelection]
    var id: String { source ?? "" }
}
