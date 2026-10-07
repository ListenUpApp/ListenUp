import Foundation
import Shared

/// The one place Match details' shared states become native values. Pure, so every state the
/// ViewModel can emit is mapped and tested without a ViewModel (`BookMatchMappingTests`).
enum BookMatchMapping {
    /// More than this many segments and a source switch becomes a menu (HIG, Segmented controls).
    static let maxSegments = 4

    /// Long text is clamped to three lines with Read all.
    static let longTextThreshold = 120

    // MARK: - Find

    static func find(from state: any FindUiState) -> MatchFind {
        let yourCopy = state.yourCopy
        switch state.sealedType() {
        case .searching(let searchingType):
            let searching = searchingType.value
            return MatchFind(
                query: searching.query,
                yourCopy: yourCopy.map { self.yourCopy($0, steps: searching.previous?.steps ?? []) },
                store: searching.previous?.region.map(storeMenu),
                phase: .searching(previous: searching.previous.map(results))
            )
        case .results(let resultsType):
            let loaded = resultsType.value
            return MatchFind(
                query: loaded.query,
                yourCopy: yourCopy.map { self.yourCopy($0, steps: loaded.steps) },
                store: loaded.region.map(storeMenu),
                phase: .results(results(loaded))
            )
        case .failed(let failedType):
            let failed = failedType.value
            return MatchFind(
                query: failed.query,
                yourCopy: yourCopy.map { self.yourCopy($0, steps: []) },
                store: failed.region.map(storeMenu),
                phase: .failed(failure(failed.failure))
            )
        }
    }

    static func yourCopy(_ copy: YourCopyUi, steps: [any SearchStep]) -> MatchYourCopy {
        MatchYourCopy(
            title: copy.title,
            coverPath: copy.coverPath,
            coverHash: copy.coverHash,
            detailLine: MatchCopy.yourCopyLine(copy),
            stepsLine: MatchCopy.stepsLine(steps),
            compare: MatchCompareValues(
                length: MatchCopy.length(ms: copy.durationMs),
                narrator: MatchCopy.narrators(copy.narrators),
                chapters: copy.chapterCount.flatMap { $0 > 0 ? String($0) : nil },
                year: copy.year.map { String($0) },
                format: MatchCopy.format(copy.isAbridged ? .abridged : .unabridged),
                store: nil,
                foundIn: String(localized: "match.in_your_library")
            )
        )
    }

    static func storeMenu(_ region: RegionUi) -> MatchStoreMenu {
        MatchStoreMenu(
            title: MatchCopy.storeButton(source: region.source, store: region.region),
            selected: MatchStoreChoice(region.region),
            choices: region.choices.map(MatchStoreChoice.init)
        )
    }

    static func results(_ results: FindUiStateResults) -> MatchResults {
        MatchResults(
            strong: results.strong.map(candidate),
            maybe: results.maybe.map(candidate),
            partial: results.partialFailure.map(MatchCopy.partialBanner),
            pickedId: results.pickedKey.flatMap { key in results.all.first { sameKey($0.key, key) }?.id }
        )
    }

    /// Whether two candidate keys name the same refs. Compared by value here rather than trusting
    /// equality across the Swift Export bridge.
    static func sameKey(_ lhs: BookCandidateKey, _ rhs: BookCandidateKey) -> Bool {
        func refs(_ key: BookCandidateKey) -> [String] {
            key.refs.map { "\($0.provider):\($0.id):\($0.region ?? "")" }.sorted()
        }
        return refs(lhs) == refs(rhs)
    }

    static func candidate(_ candidate: CandidateUi) -> MatchCandidateRow {
        let sources = MatchCopy.sourceLabels(candidate.foundIn.map(\.source))
        let stores = candidate.foundIn.compactMap { MatchCopy.storeName(regionCode: $0.region) }
        return MatchCandidateRow(
            id: candidate.id,
            title: candidate.title,
            coverURL: candidate.coverUrl,
            isStrong: candidate.tier == .strong,
            isBest: candidate.isBest,
            isCurrentLink: candidate.isCurrentLink,
            metadataLine: MatchCopy.metadataLine(candidate),
            reasons: candidate.reasons.map { MatchReasonLine(text: MatchCopy.reason($0)) },
            foundInLine: sources.joined(separator: " · "),
            foundInList: MatchCopy.list(sources),
            compare: MatchCompareValues(
                length: MatchCopy.length(ms: candidate.durationMs),
                narrator: MatchCopy.narrators(candidate.narrators),
                chapters: candidate.chapterCount.flatMap { $0 > 0 ? String($0) : nil },
                year: candidate.year.map { String($0) },
                format: candidate.format.map(MatchCopy.format),
                store: stores.first,
                foundIn: sources.isEmpty ? nil : MatchCopy.list(sources)
            )
        )
    }

    static func failure(_ failure: any FindFailure) -> MatchFailure {
        let retry = MatchFailureAction.retry(title: String(localized: "match.try_again"))
        switch failure.sealedType() {
        case .offline:
            return MatchFailure(
                systemImage: "wifi.slash",
                title: String(localized: "match.offline_title"),
                message: String(localized: "match.offline_body"),
                actions: [retry]
            )
        case .timedOut(let timedOutType):
            return MatchFailure(
                systemImage: "clock.badge.exclamationmark",
                title: String(format: String(localized: "match.timeout_title"), timedOutType.value.source.label),
                message: String(localized: "match.timeout_body"),
                actions: [retry]
            )
        case .rateLimited(let limitedType):
            return rateLimited(limitedType.value)
        case .sourceFailed(let failedType):
            return MatchFailure(
                systemImage: "exclamationmark.triangle",
                title: String(format: String(localized: "match.source_failed_title"), failedType.value.source.label),
                message: String(localized: "match.source_failed_body"),
                actions: [retry]
            )
        case .notFoundInStore(let notFoundType):
            let notFound = notFoundType.value
            let stores: [MatchFailureAction] = notFound.suggestions.prefix(2).map { locale in
                .tryStore(
                    title: String(format: String(localized: "match.try_store"), locale.displayName),
                    store: MatchStoreChoice(locale)
                )
            }
            return MatchFailure(
                systemImage: "magnifyingglass",
                title: String(format: String(localized: "match.not_found_title"), notFound.region.displayName),
                message: String(localized: "match.not_found_body"),
                actions: stores + [.searchByTitle(title: String(localized: "match.search_by_title"))]
            )
        case .nothingFound:
            return MatchFailure(
                systemImage: "magnifyingglass",
                title: String(localized: "match.nothing_found_title"),
                message: String(localized: "match.nothing_found_body"),
                actions: [.searchByTitle(title: String(localized: "match.search_by_title"))]
            )
        case .unexpected(let unexpectedType):
            return MatchFailure(
                systemImage: "exclamationmark.triangle",
                title: String(localized: "match.unexpected_title"),
                message: unexpectedType.value.error.message,
                actions: [retry]
            )
        }
    }

    /// A rate limit: Retry stays disabled, counting down, until the source will listen again.
    static func rateLimited(_ limited: FindFailureRateLimited) -> MatchFailure {
        let remaining = limited.secondsRemaining
        let retry: MatchFailureAction = remaining > 0
            ? .retryCountdown(
                title: String(format: String(localized: "match.retry_in"), MatchCopy.countdown(seconds: remaining)),
                isEnabled: false
            )
            : .retryCountdown(title: String(localized: "match.try_again"), isEnabled: true)
        return MatchFailure(
            systemImage: "hourglass",
            title: String(format: String(localized: "match.rate_limited_title"), limited.source.label),
            message: String(format: String(localized: "match.rate_limited_body"), Int(max(0, remaining))),
            actions: [retry]
        )
    }

    // MARK: - Review

    static func review(from state: any ReviewUiState, viewerId: String?) -> MatchReviewPhase {
        switch state.sealedType() {
        case .noneChosen:
            return .noneChosen
        case .loading(let loadingType):
            let candidate = loadingType.value.candidate
            return .loading(candidateId: candidate.id, title: candidate.title)
        case .failed(let failedType):
            let failed = failedType.value
            return .failed(
                candidateId: failed.candidate.id, title: failed.candidate.title, message: failed.error.message
            )
        case .ready(let readyType):
            return .ready(review(readyType.value, viewerId: viewerId))
        }
    }

    static func review(_ ready: ReviewUiStateReady, viewerId: String?) -> MatchReview {
        let candidate = candidate(ready.candidate)
        return MatchReview(
            candidate: candidate,
            foundInSentence: String(format: String(localized: "match.found_in"), candidate.foundInList),
            summary: MatchCopy.summaryItems(ready.summary),
            cover: cover(ready.cover),
            changes: ready.changes.map { field($0, viewerId: viewerId) },
            fillsGap: ready.fillsGap.map { field($0, viewerId: viewerId) },
            youEdited: ready.youEdited.map { field($0, viewerId: viewerId) },
            labels: [
                labels(ready.genres, kind: .genres, title: String(localized: "match.genres")),
                labels(ready.moods, kind: .moods, title: String(localized: "match.moods"))
            ].compactMap { $0 },
            chapters: chapters(ready.chapterNames),
            // Read as names, never as `alreadySame`: a bridged `List<BookField>`'s elements trap when Swift
            // reads them (NoBridgedEnumCollectionsInUiStateRule). The Swift enum is rebuilt from each name.
            alreadySame: MatchCopy.alreadySame(
                ready.alreadySameNames.compactMap { BookField($0) }, lengthAlreadySame: ready.lengthAlreadySame
            ),
            applyBar: MatchApplyBar(
                summary: MatchCopy.applyBar(ready.applyBar),
                canApply: ready.applyBar.canApply,
                applying: ready.applying,
                error: ready.applyError.map { MatchCopy.applyError($0.message) }
            )
        )
    }

    static func field(_ field: FieldUi, viewerId: String?) -> MatchFieldRow {
        let name = MatchCopy.fieldName(field.field)
        let proposedSources = MatchCopy.list(MatchCopy.sourceLabels(field.proposed.sources))
        let yours = field.current.map(MatchCopy.value)
        let proposed = MatchCopy.value(field.proposed.value)
        let segments = sourceSegments(field)
        let isEdited = field.state == .userEdited || field.handEdit != nil
        let tickLabel = field.state == .fillsGap
            ? String(format: String(localized: "match.field_fills_gap_a11y"), name, proposedSources)
            : String(format: String(localized: "match.field_changes_a11y"), name, proposedSources)
        return MatchFieldRow(
            field: field.field,
            name: name,
            proposedFrom: String(format: String(localized: "match.from_source"), proposedSources),
            yours: yours,
            proposed: proposed,
            isLongText: max(proposed.count, yours?.count ?? 0) > longTextThreshold,
            isTicked: field.isTicked,
            isEdited: isEdited,
            editedNote: isEdited ? MatchCopy.editedNote(field.handEdit, viewerId: viewerId) : nil,
            segments: segments,
            selectedSegment: selectedSegment(field),
            switchStyle: segments.count < 2 ? .none : (segments.count > maxSegments ? .menu : .segmented),
            tickLabel: tickLabel,
            valuesLabel: String(
                format: String(localized: "match.field_full_a11y"),
                name, MatchCopy.withoutFinalPeriod(yours ?? String(localized: "match.empty_value")), proposedSources,
                MatchCopy.withoutFinalPeriod(proposed)
            )
        )
    }

    /// "Audible | Hardcover | Keep yours": every option by its sources, and Keep yours when there is
    /// something of yours to keep.
    static func sourceSegments(_ field: FieldUi) -> [MatchSourceSegment] {
        var segments = field.options.map { option in
            MatchSourceSegment(
                selection: .option(option.optionId),
                title: MatchCopy.list(MatchCopy.sourceLabels(option.sources))
            )
        }
        if field.canKeepYours {
            segments.append(MatchSourceSegment(selection: .keepYours, title: String(localized: "match.keep_yours")))
        }
        return segments
    }

    /// The switch shows the chosen option; an unticked field shows Keep yours, or — with nothing to keep —
    /// the option re-ticking would restore.
    static func selectedSegment(_ field: FieldUi) -> MatchSourceSelection {
        if case .option(let optionType) = field.choice.sealedType() { return .option(optionType.value.optionId) }
        return field.canKeepYours ? .keepYours : .option(field.proposed.optionId)
    }

    static func cover(_ cover: CoverUi) -> MatchCoverSection? {
        guard !cover.options.isEmpty else { return nil }
        let chosenId: String? = {
            if case .candidate(let candidateType) = cover.choice.sealedType() { return candidateType.value.optionId }
            return nil
        }()
        let keep = MatchCoverTile(
            id: "keep",
            title: String(localized: "match.keep_current"),
            detail: String(localized: "match.yours"),
            url: nil,
            isKeepCurrent: true,
            isSelected: chosenId == nil,
            accessibilityLabel: String(localized: "match.keep_current_cover")
        )
        let candidates = cover.options.map { option in
            MatchCoverTile(
                id: option.optionId,
                title: option.source.label,
                detail: String(
                    format: String(localized: "match.cover_dimensions"), Int(option.width), Int(option.height)
                ),
                url: option.url,
                isKeepCurrent: false,
                isSelected: option.optionId == chosenId,
                accessibilityLabel: String(
                    format: String(localized: "match.cover_from_a11y"),
                    option.source.label, Int(option.width), Int(option.height)
                )
            )
        }
        return MatchCoverSection(
            chosenSource: cover.chosen?.source.label,
            tiles: [keep] + candidates,
            // `CoverUi.current` is a contract type Swift Export references but doesn't declare, so it
            // has no members here; the path is enough for the cover to resolve.
            bookCoverPath: cover.currentCoverPath
        )
    }

    static func labels(_ set: LabelSetUi, kind: LabelKind, title: String) -> MatchLabelGroup? {
        guard !set.yours.isEmpty || !set.suggested.isEmpty else { return nil }
        return MatchLabelGroup(
            kind: kind,
            title: title,
            yours: set.yours.map { MatchYourLabel(label: $0.label, removed: $0.removed) },
            suggested: set.suggested.map { suggestion in
                let sources = MatchCopy.list(MatchCopy.sourceLabels(suggestion.sources))
                return MatchSuggestion(
                    label: suggestion.label,
                    sources: sources,
                    selected: suggestion.selected,
                    accessibilityLabel: String(
                        format: String(localized: "match.suggestion_a11y"), suggestion.label, sources
                    )
                )
            }
        )
    }

    static func chapters(_ names: any ChapterNamesUi) -> MatchChapterSection? {
        switch names.sealedType() {
        case .hidden:
            return nil
        case .countMismatch(let mismatchType):
            let mismatch = mismatchType.value
            return .mismatch(message: String(
                format: String(localized: "match.chapter_count_mismatch"),
                mismatch.source.label, Int(mismatch.theirs), Int(mismatch.yours)
            ))
        case .available(let availableType):
            let available = availableType.value
            let changing = available.rows.count
            return .available(
                summary: String(
                    format: String(localized: "match.chapter_names_from"),
                    changing, changing + Int(available.unchangedCount), available.source.label,
                    Int(available.unchangedCount)
                ),
                included: available.included,
                rows: available.rows.map { row in
                    MatchChapterRow(
                        ordinal: row.ordinal,
                        yours: row.yours,
                        theirs: row.theirs,
                        selected: row.selected,
                        accessibilityLabel: String(
                            format: String(localized: "match.chapter_row_a11y"), Int(row.ordinal), row.yours, row.theirs
                        )
                    )
                }
            )
        }
    }

    // MARK: - Receipt

    static func receipt(from state: any MatchReceiptUiState) -> MatchReceiptPhase {
        switch state.sealedType() {
        case .none:
            return .none
        case .undone:
            return .undone
        case .expired:
            return .expired
        case .shown(let shownType):
            let shown = shownType.value
            return .shown(MatchReceiptModel(
                id: shown.receipt.receiptId,
                sentence: MatchCopy.receipt(shown.receipt),
                changes: shown.receipt.changes.flatMap(MatchCopy.changeLines),
                canUndo: shown.receipt.undoable,
                undoing: shown.undoing,
                undoError: shown.undoError?.message
            ))
        }
    }
}
