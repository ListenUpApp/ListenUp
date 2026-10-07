import Foundation
import Shared
@testable import ListenUp

/// Hand-built shared states for Match details' tests. The sources carry made-up names: matching code
/// never names a real provider, and its tests prove the label comes from `MetadataSource.label`.
enum MatchFixtures {
    static var storefront: MetadataSource { MetadataSource(id: "storefront", label: "Storefront") }
    static var shelfdata: MetadataSource { MetadataSource(id: "shelfdata", label: "Shelfdata") }
    static var tuneshop: MetadataSource { MetadataSource(id: "tuneshop", label: "Tuneshop") }

    static var unitedStates: MetadataLocale { MetadataLocale(region: "us", language: nil) }

    static func key(_ id: String) -> BookCandidateKey {
        BookCandidateKey(refs: [ExternalRef(provider: "storefront", id: id, region: "us")])
    }

    static func candidate(
        id: String = "storefront:B1:us",
        title: String = "Project Hail Mary",
        narrators: [String] = ["Ray Porter"],
        durationMs: Int64? = 58_200_000,
        year: Int32? = 2021,
        format: EditionFormat? = .unabridged,
        chapterCount: Int32? = 36,
        foundIn: [FoundIn] = [
            FoundIn(source: storefront, region: "us"),
            FoundIn(source: shelfdata, region: nil),
            FoundIn(source: tuneshop, region: nil)
        ],
        isStrong: Bool = true,
        isBest: Bool = true,
        isCurrentLink: Bool = false,
        reasons: [any MatchReason] = [
            MatchReasonSameNarrator.shared, MatchReasonSameLength.shared, MatchReasonSameChapterCount(count: 36)
        ]
    ) -> CandidateUi {
        CandidateUi(
            id: id, key: key(id), title: title, subtitle: nil, authors: ["Andy Weir"], narrators: narrators,
            durationMs: durationMs, year: year, format: format, chapterCount: chapterCount,
            coverUrl: "https://example.com/c.jpg", foundIn: foundIn, tier: isStrong ? .strong : .maybe, isBest: isBest,
            isCurrentLink: isCurrentLink, reasons: reasons
        )
    }

    static func text(_ value: String) -> any FieldValue { FieldValueText(text: value) }

    static func field(
        _ field: BookField = .description,
        state: FieldState = .changes,
        current: (any FieldValue)? = FieldValueText(text: "A lone astronaut wakes far from home."),
        options: [FieldOptionUi]? = nil,
        ticked: Bool = true,
        handEdit: HandEdit? = nil
    ) -> FieldUi {
        let options = options ?? [
            FieldOptionUi(optionId: "o1", value: text("Ryland Grace is the sole survivor."), sources: [storefront]),
            FieldOptionUi(optionId: "o2", value: text("A science teacher wakes up alone."), sources: [shelfdata])
        ]
        let choice: any FieldChoice = ticked ? FieldChoiceOption(optionId: options[0].optionId) : FieldChoiceKeepCurrent.shared
        return FieldUi(
            field: field, state: state, current: current, options: options, choice: choice,
            proposed: options[0], handEdit: handEdit
        )
    }

    static func cover(choice: any ImageChoice = ImageChoiceCandidate(optionId: "c2")) -> CoverUi {
        CoverUi(
            current: nil,
            currentCoverPath: "/covers/b1.jpg",
            options: [
                CoverCandidate(optionId: "c1", source: storefront, url: "https://example.com/1.jpg", width: 2400, height: 2400),
                CoverCandidate(optionId: "c2", source: shelfdata, url: "https://example.com/2.jpg", width: 1600, height: 2400)
            ],
            choice: choice
        )
    }

    static func ready(
        changes: [FieldUi] = [field()],
        fillsGap: [FieldUi] = [],
        youEdited: [FieldUi] = [],
        cover: CoverUi = cover(),
        genres: LabelSetUi = LabelSetUi(yours: [], suggested: []),
        moods: LabelSetUi = LabelSetUi(yours: [], suggested: []),
        chapterNames: any ChapterNamesUi = ChapterNamesUiHidden.shared,
        alreadySame: [BookField] = [],
        lengthAlreadySame: Bool = false,
        applyBar: ApplySummary = ApplySummary(fieldCount: 1, coverChanges: true, chapterNameCount: 0),
        applying: Bool = false,
        applyError: (any AppError)? = nil
    ) -> ReviewUiStateReady {
        ReviewUiStateReady(
            candidate: candidate(),
            summary: WhatWillChange(
                coverSource: shelfdata, changeCount: Int32(changes.count), gapCount: Int32(fillsGap.count),
                labelsAdded: 0, labelsRemoved: 0, chapterNameCount: 0, keptEditedCount: Int32(youEdited.count)
            ),
            cover: cover, changes: changes, fillsGap: fillsGap, youEdited: youEdited, genres: genres, moods: moods,
            chapterNames: chapterNames, alreadySame: alreadySame, lengthAlreadySame: lengthAlreadySame,
            applyBar: applyBar, applying: applying, applyError: applyError
        )
    }

    /// 12 Sep 2026, midday UTC — the same calendar day in every time zone a test runs in.
    static let twelfthOfSeptember: Int64 = 1_789_214_400_000
}
