import Foundation
import Shared

/// Every sentence Match details composes, as pure functions over the shared types — so each one is
/// tested once, here, and the views only place text. Provider names come from `MetadataSource.label`;
/// none is ever written in this file.
enum MatchCopy {
    // MARK: - Lists

    /// "A", "A and B", "A, B and C".
    static func list(_ items: [String]) -> String {
        switch items.count {
        case 0: return ""
        case 1: return items[0]
        case 2: return String(format: String(localized: "match.list_two"), items[0], items[1])
        default: return String(format: String(localized: "match.list_many"), items[0], list(Array(items.dropFirst())))
        }
    }

    /// Each source once, in the order given.
    static func sourceLabels(_ sources: [MetadataSource]) -> [String] {
        var seen = Set<String>()
        return sources.map(\.label).filter { seen.insert($0).inserted }
    }

    // MARK: - Find

    /// One reason a candidate matches your copy, or doesn't.
    static func reason(_ reason: any MatchReason) -> String {
        switch reason.sealedType() {
        case .sameNarrator:
            return String(localized: "match.reason_same_narrator")
        case .differentNarrators:
            return String(localized: "match.reason_different_narrators")
        case .sameLength:
            return String(localized: "match.reason_same_length")
        case .lengthWithin(let withinType):
            return String(format: String(localized: "match.reason_length_within"), Int(withinType.value.minutes))
        case .lengthDiffers(let differsType):
            let delta = Int64(differsType.value.deltaMinutes)
            let amount = DurationFormatting.hoursMinutes(ms: abs(delta) * 60_000)
            let pattern = delta < 0
                ? String(localized: "match.reason_shorter")
                : String(localized: "match.reason_longer")
            return String(format: pattern, amount)
        case .lengthUnknown:
            return String(localized: "match.reason_length_unknown")
        case .sameChapterCount(let sameType):
            return String(format: String(localized: "match.reason_same_chapter_count"), Int(sameType.value.count))
        case .differentChapterCount(let differentType):
            return String(
                format: String(localized: "match.reason_different_chapter_count"), Int(differentType.value.theirs)
            )
        case .differentStore(let storeType):
            return String(format: String(localized: "match.reason_different_store"), storeType.value.region.displayName)
        case .differentEdition(let editionType):
            return String(format: String(localized: "match.reason_different_edition"), format(editionType.value.format))
        }
    }

    /// "Unabridged", "Abridged", "Dramatized".
    static func format(_ format: EditionFormat) -> String {
        switch format {
        case .unabridged: String(localized: "match.format_unabridged")
        case .abridged: String(localized: "match.format_abridged")
        case .dramatized: String(localized: "match.format_dramatized")
        }
    }

    /// Narrators as a row says them: up to two names, then "Full cast".
    static func narrators(_ names: [String]) -> String? {
        if names.isEmpty { return nil }
        if names.count > 2 { return String(localized: "match.full_cast") }
        return names.joined(separator: ", ")
    }

    /// "16h 10m", or nil when unknown.
    static func length(ms: Int64?) -> String? {
        guard let ms, ms > 0 else { return nil }
        return DurationFormatting.hoursMinutes(ms: ms)
    }

    /// "36 chapters" / "1 chapter".
    static func chapters(_ count: Int32?) -> String? {
        guard let count, count > 0 else { return nil }
        if count == 1 { return String(localized: "match.chapter_count_one") }
        return String(format: String(localized: "match.chapters_count"), Int(count))
    }

    /// "Ray Porter · 16h 10m · 2021 · Unabridged".
    static func metadataLine(_ candidate: CandidateUi) -> String {
        [
            narrators(candidate.narrators),
            length(ms: candidate.durationMs),
            candidate.year.map { String($0) },
            candidate.format.map(format)
        ]
        .compactMap { $0 }
        .joined(separator: " · ")
    }

    /// "16h 10m · Ray Porter · 36 chapters".
    static func yourCopyLine(_ copy: YourCopyUi) -> String? {
        let parts = [length(ms: copy.durationMs), narrators(copy.narrators), chapters(copy.chapterCount)]
            .compactMap { $0 }
        return parts.isEmpty ? nil : parts.joined(separator: " · ")
    }

    /// "Started from your Audible link, then title, author and length."
    static func stepsLine(_ steps: [any SearchStep]) -> String? {
        let phrases = steps.map(step)
        guard var joined = phrases.first else { return nil }
        for next in phrases.dropFirst() {
            joined = String(format: String(localized: "match.steps_join"), joined, next)
        }
        return String(format: String(localized: "match.steps_started_from"), joined)
    }

    private static func step(_ step: any SearchStep) -> String {
        switch step.sealedType() {
        case .existingLink(let linkType):
            return String(format: String(localized: "match.step_existing_link"), linkType.value.source.label)
        case .identifier(let identifierType):
            switch identifierType.value.kind {
            case .asin: return String(localized: "match.step_asin")
            case .isbn: return String(localized: "match.step_isbn")
            }
        case .titleAuthorLength:
            return String(localized: "match.step_title_author_length")
        case .yourQuery(let queryType):
            return String(format: String(localized: "match.step_your_query"), queryType.value.query)
        }
    }

    /// The store a `FoundIn` region code names, or nil when the source has no stores.
    static func storeName(regionCode: String?) -> String? {
        guard let regionCode, !regionCode.isEmpty else { return nil }
        return MetadataLocale(region: regionCode, language: nil).displayName
    }

    /// "Audible store: United States".
    static func storeButton(source: MetadataSource, store: MetadataLocale) -> String {
        String(format: String(localized: "match.store_button"), source.label, store.displayName)
    }

    /// "Hardcover didn't answer, so these results are from Audible and iTunes."
    static func partialBanner(_ partial: PartialFailure) -> MatchPartialBanner {
        let failed = list(sourceLabels(partial.failed))
        return MatchPartialBanner(
            message: String(
                format: String(localized: "match.partial_banner"), failed, list(sourceLabels(partial.answered))
            ),
            retryTitle: String(format: String(localized: "match.retry_source"), failed)
        )
    }

    /// "0:30" for a rate limit's countdown.
    static func countdown(seconds: Int32) -> String {
        let clamped = max(0, Int(seconds))
        return String(format: "%d:%02d", clamped / 60, clamped % 60)
    }

    /// The "N matches" announcement when results land.
    static func matchesAnnouncement(_ count: Int) -> String {
        count == 1
            ? String(localized: "match.match_announcement_one")
            : String(format: String(localized: "match.matches_announcement"), count)
    }

    // MARK: - Review

    /// A field's name as Review heads it.
    static func fieldName(_ field: BookField) -> String {
        if let key = fieldNameKeys[field] { return String(localized: String.LocalizationValue(key)) }
        // Fields Review never shows; named plainly rather than crashing if one ever arrives.
        let raw = String(describing: field)
        return field == .isbn || field == .asin ? raw : raw.replacingOccurrences(of: "_", with: " ").capitalized
    }

    private static let fieldNameKeys: [BookField: String] = [
        .title: "match.field_title",
        .subtitle: "match.field_subtitle",
        .description: "match.field_description",
        .publisher: "match.field_publisher",
        .publishYear: "match.field_release_date",
        .language: "match.field_language",
        .authors: "match.field_authors",
        .narrators: "match.field_narrators",
        .series: "match.field_series",
        .genres: "match.genres",
        .moods: "match.moods",
        .cover: "match.section_cover",
        .chapters: "match.section_chapter_names"
    ]

    /// A value as Review shows it. Release date is the year only; descriptions lose their markup.
    static func value(_ value: any FieldValue) -> String {
        switch value.sealedType() {
        case .text(let textType):
            return plainText(textType.value.text)
        case .people(let peopleType):
            return peopleType.value.names.joined(separator: ", ")
        case .seriesEntries(let seriesType):
            return seriesType.value.entries.map { entry in
                guard let sequence = entry.sequence, !sequence.isEmpty else { return entry.name }
                return String(format: String(localized: "match.series_entry"), entry.name, sequence)
            }
            .joined(separator: ", ")
        case .year(let yearType):
            return String(yearType.value.year)
        }
    }

    /// A value placed before a sentence's own full stop, so "survivor." doesn't read "survivor..".
    static func withoutFinalPeriod(_ text: String) -> String {
        text.hasSuffix(".") && !text.hasSuffix("...") ? String(text.dropLast()) : text
    }

    /// Text with any HTML or Markdown rendered away, as the book's own description surfaces read it.
    static func plainText(_ text: String) -> String {
        String(AttributedString.fromBookMarkdown(text).characters)
            .trimmingCharacters(in: .whitespacesAndNewlines)
    }

    /// "Edited by you, 12 Sep. Kept unless you tick it." — "you" when the viewer made the edit, their name
    /// when someone else did, "hand" when nobody is known; the date only when the edit has one.
    static func editedNote(_ edit: HandEdit?, viewerId: String?, locale: Locale = .current) -> String {
        let date = edit?.at.flatMap { $0 > 0 ? day($0, locale: locale) : nil }
        if let byUserId = edit?.byUserId, let viewerId, byUserId == viewerId {
            return date.map { String(format: String(localized: "match.edited_by_you"), $0) }
                ?? String(localized: "match.edited_by_you_undated")
        }
        if let name = edit?.byName, !name.isEmpty {
            return date.map { String(format: String(localized: "match.edited_by_name"), name, $0) }
                ?? String(format: String(localized: "match.edited_by_name_undated"), name)
        }
        return date.map { String(format: String(localized: "match.edited_by_hand"), $0) }
            ?? String(localized: "match.edited_by_hand_undated")
    }

    /// "12 Sep" in the reader's own order.
    static func day(_ epochMs: Int64, locale: Locale) -> String {
        Date(timeIntervalSince1970: TimeInterval(epochMs) / 1000)
            .formatted(.dateTime.day().month(.abbreviated).locale(locale))
    }

    /// "6 fields already match: Authors, Narrators, Series, Language, Subtitle, Length".
    static func alreadySame(_ fields: [BookField], lengthAlreadySame: Bool) -> String? {
        var names = fields.map(fieldName)
        if lengthAlreadySame { names.append(String(localized: "match.field_length")) }
        guard !names.isEmpty else { return nil }
        let joined = names.joined(separator: ", ")
        return names.count == 1
            ? String(format: String(localized: "match.already_same_one"), joined)
            : String(format: String(localized: "match.already_same_summary"), names.count, joined)
    }

    /// "5 fields · cover · 16 chapter names", or "Nothing selected".
    static func applyBar(_ summary: ApplySummary) -> String {
        var parts: [String] = []
        if summary.fieldCount == 1 {
            parts.append(String(localized: "match.bar_field_one"))
        } else if summary.fieldCount > 1 {
            parts.append(String(format: String(localized: "match.bar_fields"), Int(summary.fieldCount)))
        }
        if summary.coverChanges { parts.append(String(localized: "match.bar_cover")) }
        if summary.chapterNameCount == 1 {
            parts.append(String(localized: "match.bar_chapter_name_one"))
        } else if summary.chapterNameCount > 1 {
            parts.append(String(format: String(localized: "match.bar_chapter_names"), Int(summary.chapterNameCount)))
        }
        return parts.isEmpty ? String(localized: "match.bar_nothing") : parts.joined(separator: " · ")
    }

    /// An apply error as the bar shows it: the error's own sentence, then "Nothing was changed." unless the
    /// sentence already says so.
    static func applyError(_ message: String) -> String {
        let nothing = String(localized: "match.nothing_was_changed")
        return message.contains(nothing) ? message : "\(message) \(nothing)"
    }

    /// The What will change counts, in section order; a zero count is left out.
    static func summaryItems(_ summary: WhatWillChange) -> [MatchSummaryItem] {
        var items: [MatchSummaryItem] = []
        if let source = summary.coverSource {
            items.append(MatchSummaryItem(
                section: .cover,
                value: String(localized: "match.sum_cover"),
                caption: String(format: String(localized: "match.sum_from"), source.label)
            ))
        }
        func add(_ count: Int32, _ section: MatchReviewSection, one: String, many: String) {
            guard count > 0 else { return }
            items.append(MatchSummaryItem(section: section, value: String(count), caption: count == 1 ? one : many))
        }
        add(summary.changeCount, .changes,
            one: String(localized: "match.sum_change_one"), many: String(localized: "match.sum_changes"))
        add(summary.gapCount, .fillsGap,
            one: String(localized: "match.sum_gap_one"), many: String(localized: "match.sum_gaps"))
        add(summary.keptEditedCount, .youEdited,
            one: String(localized: "match.sum_kept"), many: String(localized: "match.sum_kept"))
        add(summary.labelsAdded + summary.labelsRemoved, .labels,
            one: String(localized: "match.sum_labels"), many: String(localized: "match.sum_labels"))
        add(summary.chapterNameCount, .chapterNames,
            one: String(localized: "match.sum_chapter_names"), many: String(localized: "match.sum_chapter_names"))
        return items
    }

    /// A toggle's value: "Selected" or "Not selected".
    static func selectionValue(_ isOn: Bool) -> String {
        isOn ? String(localized: "match.selected") : String(localized: "match.not_selected")
    }

    // MARK: - Receipt

    /// "Changed 5 fields, cover from Hardcover, 16 chapter names".
    static func receipt(_ receipt: MatchReceiptUi) -> String {
        var parts: [String] = []
        if receipt.fieldCount == 1 {
            parts.append(String(localized: "match.receipt_field_one"))
        } else if receipt.fieldCount > 1 {
            parts.append(String(format: String(localized: "match.receipt_fields"), Int(receipt.fieldCount)))
        }
        if let cover = receipt.coverSource {
            parts.append(String(format: String(localized: "match.receipt_cover_from"), cover.label))
        }
        if receipt.chapterNameCount == 1 {
            parts.append(String(localized: "match.receipt_chapter_name_one"))
        } else if receipt.chapterNameCount > 1 {
            let pattern = String(localized: "match.receipt_chapter_names")
            parts.append(String(format: pattern, Int(receipt.chapterNameCount)))
        }
        guard !parts.isEmpty else { return String(localized: "match.receipt_nothing") }
        return String(format: String(localized: "match.receipt_changed"), parts.joined(separator: ", "))
    }

    /// One applied change as See What Changed lists it, with its source.
    static func changeLines(_ change: any AppliedChange) -> [String] {
        switch change.sealedType() {
        case .field(let fieldType):
            let field = fieldType.value
            return [String(format: String(localized: "match.change_field"), fieldName(field.field), field.source.label)]
        case .cover(let coverType):
            return [String(format: String(localized: "match.change_cover"), coverType.value.source.label)]
        case .genres(let genresType):
            return labelLines(
                added: genresType.value.added, removed: genresType.value.removed,
                addedKey: String(localized: "match.change_genres_added"),
                removedKey: String(localized: "match.change_genres_removed")
            )
        case .moods(let moodsType):
            return labelLines(
                added: moodsType.value.added, removed: moodsType.value.removed,
                addedKey: String(localized: "match.change_moods_added"),
                removedKey: String(localized: "match.change_moods_removed")
            )
        case .chapterNames(let chaptersType):
            let names = chaptersType.value
            let pattern = String(localized: "match.change_chapter_names")
            return [String(format: pattern, Int(names.count), names.source.label)]
        case .photo(let photoType):
            return [String(format: String(localized: "match.change_photo"), photoType.value.source.label)]
        case .biography(let biographyType):
            return [String(format: String(localized: "match.change_biography"), biographyType.value.source.label)]
        }
    }

    private static func labelLines(
        added: [String], removed: [String], addedKey: String, removedKey: String
    ) -> [String] {
        var lines: [String] = []
        if !added.isEmpty { lines.append(String(format: addedKey, added.joined(separator: ", "))) }
        if !removed.isEmpty { lines.append(String(format: removedKey, removed.joined(separator: ", "))) }
        return lines
    }
}
