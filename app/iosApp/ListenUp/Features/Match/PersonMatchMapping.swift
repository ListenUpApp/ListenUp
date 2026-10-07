import Foundation
import Shared

/// The one place person Match details' shared states become native values. Pure, so every state the
/// ViewModel can emit is mapped and tested without a ViewModel (`PersonMatchMappingTests`).
enum PersonMatchMapping {
    // MARK: - Find

    static func find(from state: any PersonFindUiState) -> PersonFind {
        let role = PersonMatchRole(state.role) ?? .author
        let name = state.header?.name ?? ""
        func make(coverage: CoverageNote?, steps: [any PersonSearchStep], phase: PersonFindPhase) -> PersonFind {
            PersonFind(
                role: role,
                name: name,
                subtitle: name.isEmpty ? "" : MatchCopy.personSubtitle(name: name, role: role),
                searchPrompt: MatchCopy.personSearchPrompt(role),
                query: state.query,
                library: state.inLibrary.flatMap { library($0, role: role) },
                coverageNote: coverage.map { MatchCopy.coverageNote($0, role: role) },
                stepsLine: MatchCopy.personStepsLine(steps, name: name, role: role),
                phase: phase
            )
        }
        switch state.sealedType() {
        case .searching(let searchingType):
            let previous = searchingType.value.previous
            return make(
                coverage: previous?.coverageNote,
                steps: previous?.steps ?? [],
                phase: .searching(previous: previous.map { results($0, role: role) })
            )
        case .results(let resultsType):
            let loaded = resultsType.value
            return make(coverage: loaded.coverageNote, steps: loaded.steps, phase: .results(results(loaded, role: role)))
        case .noProfiles(let noProfilesType):
            return make(
                coverage: noProfilesType.value.coverageNote, steps: [], phase: .noProfiles(noProfiles(role))
            )
        case .failed(let failedType):
            return make(coverage: nil, steps: [], phase: .failed(failure(failedType.value.failure)))
        }
    }

    /// The Your-library strip, or nil when the person has no books here in this role.
    static func library(_ library: InLibraryUi, role: PersonMatchRole) -> PersonLibraryStrip? {
        let count = Int(library.bookCount)
        guard count > 0 else { return nil }
        return PersonLibraryStrip(
            line: MatchCopy.libraryStripLine(count: count, titles: library.titles, role: role),
            covers: library.covers.map {
                PersonLibraryCover(id: $0.bookId, title: $0.title, coverPath: $0.coverPath, coverHash: $0.coverHash)
            }
        )
    }

    static func results(_ results: PersonFindUiStateResults, role: PersonMatchRole) -> PersonResults {
        PersonResults(
            strong: results.strong.map { candidate($0, searched: role) },
            maybe: results.maybe.map { candidate($0, searched: role) },
            partial: results.partialFailure.map(MatchCopy.partialBanner),
            pickedId: results.pickedKey.flatMap { key in results.all.first { sameKey($0.key, key) }?.id }
        )
    }

    /// Whether two person keys name the same refs. Compared by value rather than trusting equality across
    /// the Swift Export bridge.
    static func sameKey(_ lhs: PersonCandidateKey, _ rhs: PersonCandidateKey) -> Bool {
        func refs(_ key: PersonCandidateKey) -> [String] {
            key.refs.map { "\($0.provider):\($0.id):\($0.region ?? "")" }.sorted()
        }
        return refs(lhs) == refs(rhs)
    }

    static func candidate(_ candidate: PersonCandidateUi, searched: PersonMatchRole) -> PersonCandidateRow {
        let sources = MatchCopy.sourceLabels(candidate.foundIn)
        let roleWord = MatchCopy.roleWord(candidate.shownRole)
        let works = MatchCopy.works(knownWorks: candidate.knownWorks, worksCount: candidate.worksCount.map { Int($0) })
        let notInRole = candidate.isDifferentRole ? MatchCopy.notInRole(searched) : nil
        let libraryLine = candidate.noBooksInLibrary
            ? String(localized: "match.no_books_in_library")
            : MatchCopy.libraryLine(count: Int(candidate.libraryCount), role: searched)
        let differentRole = candidate.isDifferentRole ? String(localized: "match.different_role") : nil

        // "Author, The Martian and Artemis. Not a narrator. Different role" — the role and works as a sentence.
        let worksSentence = candidate.knownWorks.isEmpty ? works : MatchCopy.list(candidate.knownWorks)
        let roleSentence = [[roleWord, worksSentence].compactMap { $0 }.joined(separator: ", "), notInRole, differentRole]
            .compactMap { $0 }
            .filter { !$0.isEmpty }
            .joined(separator: ". ")
        let parts = [candidate.name, roleSentence, libraryLine, MatchCopy.list(sources)].filter { !$0.isEmpty }
        let spoken = parts.count == 4
            ? String(format: String(localized: "match.person_row_a11y"), parts[0], parts[1], parts[2], parts[3])
            : parts.map(MatchCopy.withoutFinalPeriod).joined(separator: ". ") + "."
        let badges = [
            candidate.isBest ? String(localized: "match.best_match") : nil,
            candidate.isCurrentLink ? String(localized: "match.your_current_link") : nil
        ].compactMap { $0 }

        return PersonCandidateRow(
            id: candidate.id,
            name: candidate.name,
            photoURL: candidate.photoUrl,
            isStrong: candidate.tier == .strong,
            isBest: candidate.isBest,
            isCurrentLink: candidate.isCurrentLink,
            roleLine: [roleWord, works, notInRole].compactMap { $0 }.joined(separator: " · "),
            libraryLine: libraryLine,
            differentRole: differentRole,
            sourcesLine: sources.joined(separator: " · "),
            sourcesList: MatchCopy.list(sources),
            shownRole: roleWord,
            accessibilityLabel: (badges.map { "\($0). " }.joined()) + spoken
        )
    }

    static func noProfiles(_ role: PersonMatchRole) -> PersonNoProfiles {
        PersonNoProfiles(
            title: role == .author
                ? String(localized: "match.no_profiles_author_title")
                : String(localized: "match.no_profiles_narrator_title"),
            message: String(localized: "match.no_profiles_body"),
            editTitle: String(localized: "match.edit_by_hand_title")
        )
    }

    /// A book Find's failure, keeping only the ways forward a person search has: Retry. Stores and
    /// search-by-title belong to books.
    static func failure(_ failure: any FindFailure) -> MatchFailure {
        let base = BookMatchMapping.failure(failure)
        let retries = base.actions.filter {
            switch $0 {
            case .retry, .retryCountdown: true
            case .tryStore, .searchByTitle: false
            }
        }
        return MatchFailure(
            systemImage: base.systemImage,
            title: base.title,
            message: base.message,
            actions: retries.isEmpty ? [.retry(title: String(localized: "match.try_again"))] : retries
        )
    }

    // MARK: - Review

    static func review(from state: any PersonReviewUiState, viewerId: String?) -> PersonReviewPhase {
        switch state.sealedType() {
        case .noneChosen:
            return .noneChosen
        case .loading(let loadingType):
            let candidate = loadingType.value.candidate
            return .loading(candidateId: candidate.id, name: candidate.name)
        case .failed(let failedType):
            let failed = failedType.value
            return .failed(candidateId: failed.candidate.id, name: failed.candidate.name, message: failed.error.message)
        case .ready(let readyType):
            return .ready(review(readyType.value, viewerId: viewerId))
        }
    }

    static func review(_ ready: PersonReviewUiStateReady, viewerId: String?) -> PersonReview {
        let searched = PersonMatchRole(ready.role) ?? .author
        let row = candidate(ready.candidate, searched: searched)
        let roleWord = row.shownRole ?? MatchCopy.roleWord(searched.contributorRole) ?? ""
        return PersonReview(
            candidateId: row.id,
            header: PersonReviewHeader(
                name: row.name,
                photoURL: row.photoURL,
                isStrong: row.isStrong,
                isBest: row.isBest,
                isCurrentLink: row.isCurrentLink,
                roleLine: row.sourcesList.isEmpty
                    ? roleWord
                    : String(format: String(localized: "match.person_header_from"), roleWord, row.sourcesList),
                libraryLine: row.libraryLine
            ),
            whatWillChange: MatchCopy.personWhatWillChange(ready.applyBar),
            photo: ready.photo.map(photo),
            biography: ready.biography.map { biography($0, viewerId: viewerId) },
            applyBar: MatchApplyBar(
                summary: MatchCopy.personApplyBar(ready.applyBar),
                canApply: ready.applyBar.canApply,
                applying: ready.applying,
                error: ready.applyError.map { MatchCopy.applyError($0.message) }
            )
        )
    }

    /// The photo: Keep current shown with the real photo, a tile per source, and the one chosen.
    static func photo(_ photo: PhotoUi) -> PersonPhotoSection {
        let segments = photo.options.map {
            MatchSourceSegment(selection: .option($0.optionId), title: $0.source.label)
        } + [MatchSourceSegment(selection: .keepYours, title: String(localized: "match.keep_current"))]
        return PersonPhotoSection(
            stateTitle: MatchCopy.sectionState(photo.state),
            isTicked: photo.isTicked,
            proposedFrom: String(format: String(localized: "match.from_source"), photo.proposed.source.label),
            currentPath: photo.currentPath,
            yoursCaption: photo.currentPath == nil
                ? String(localized: "match.yours_no_photo")
                : String(localized: "match.your_photo"),
            proposedURL: photo.proposed.url,
            proposedLabel: String(format: String(localized: "match.photo_from_a11y"), photo.proposed.source.label),
            segments: segments,
            selectedSegment: photo.chosen.map { .option($0.optionId) } ?? .keepYours,
            switchStyle: switchStyle(segments.count),
            setByHandNote: photo.setByHand ? String(localized: "match.photo_set_by_hand") : nil
        )
    }

    /// The biography: Yours → Proposed with the PR 5 field rules — Keep yours only when there is something
    /// to keep, a hand edit flagged and unticked, the same text collapsed to a sentence.
    static func biography(_ biography: BiographyUi, viewerId: String?) -> PersonBiographySection {
        let name = String(localized: "match.section_biography")
        let sources = MatchCopy.list(MatchCopy.sourceLabels(biography.proposed.sources))
        let yours = biography.current.map(MatchCopy.plainText)
        let proposed = MatchCopy.value(biography.proposed.value)
        let isEdited = biography.state == .userEdited || biography.handEdit != nil
        var segments = biography.options.map {
            MatchSourceSegment(selection: .option($0.optionId), title: MatchCopy.list(MatchCopy.sourceLabels($0.sources)))
        }
        if biography.canKeepYours {
            segments.append(MatchSourceSegment(selection: .keepYours, title: String(localized: "match.keep_yours")))
        }
        let selected: MatchSourceSelection = {
            if case .option(let optionType) = biography.choice.sealedType() { return .option(optionType.value.optionId) }
            return biography.canKeepYours ? .keepYours : .option(biography.proposed.optionId)
        }()
        return PersonBiographySection(
            stateTitle: MatchCopy.sectionState(biography.state),
            alreadySame: biography.state == .same ? String(localized: "match.biography_already_same") : nil,
            isTicked: biography.isTicked,
            proposedFrom: String(format: String(localized: "match.from_source"), sources),
            values: MatchValues(
                name: name,
                yours: yours,
                proposed: proposed,
                isLongText: max(proposed.count, yours?.count ?? 0) > BookMatchMapping.longTextThreshold,
                accessibilityLabel: String(
                    format: String(localized: "match.field_full_a11y"),
                    name, MatchCopy.withoutFinalPeriod(yours ?? String(localized: "match.empty_value")), sources,
                    MatchCopy.withoutFinalPeriod(proposed)
                )
            ),
            isEdited: isEdited,
            editedNote: isEdited ? MatchCopy.editedNote(biography.handEdit, viewerId: viewerId) : nil,
            segments: segments,
            selectedSegment: selected,
            switchStyle: switchStyle(segments.count)
        )
    }

    /// Segments up to four, a menu beyond, nothing with no choice (HIG, Segmented controls).
    static func switchStyle(_ count: Int) -> MatchSourceSwitchStyle {
        count < 2 ? .none : (count > BookMatchMapping.maxSegments ? .menu : .segmented)
    }
}
