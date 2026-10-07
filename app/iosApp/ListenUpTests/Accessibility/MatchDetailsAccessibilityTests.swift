import SwiftUI
import Testing
import Shared
@testable import ListenUp

/// Match details for VoiceOver and large text (spec, "Accessibility requirements"): headings, toggles and
/// radios that say what they are, names that say what and where, 44-point targets, and an Apply tray that
/// leaves the screen to the fields at AX5.
@MainActor
@Suite("Match details accessibility", .serialized)
struct MatchDetailsAccessibilityTests {
    private typealias Fixture = MatchFixtures

    private func review(_ ready: ReviewUiStateReady = Fixture.ready()) -> MatchReview {
        BookMatchMapping.review(ready, viewerId: "u1")
    }

    private func hostedReview(_ review: MatchReview, size: DynamicTypeSize = .large) async -> HostedView {
        await HostedView(
            MatchReviewContent(review: review, bookId: nil),
            size: CGSize(width: 393, height: 3000),
            dynamicTypeSize: size
        )
    }

    // MARK: - Headings

    @Test func everyReviewSectionIsAHeading() async throws {
        let genres = LabelSetUi(
            yours: [YourLabelUi(label: "Science Fiction", removed: false)],
            suggested: [SuggestionUi(label: "Thriller", sources: [Fixture.shelfdata], selected: true)]
        )
        let hosted = await hostedReview(review(Fixture.ready(genres: genres, alreadySame: [.authors])))
        defer { hosted.close() }
        for title in ["Cover", "Changes", "Genres & moods", "Already the same"] {
            let heading = try #require(hosted.stops(labelContaining: title).first { $0.isHeader }, "\(title)\n\(hosted.tree)")
            #expect(heading.isHeader)
        }
    }

    // MARK: - Ticks

    /// "Description, proposed from Storefront, changes yours" — what and where, and its state in words.
    @Test func aFieldTickSaysWhatItAppliesAndWhetherItWill() async throws {
        let row = BookMatchMapping.field(Fixture.field(), viewerId: nil)
        let hosted = await HostedView(List { MatchFieldRowView(row: row, onTick: { _ in }, onChooseSource: { _ in }) })
        defer { hosted.close() }
        let tick = try #require(hosted.stop(labelled: row.tickLabel), "\(hosted.tree)")
        #expect(tick.value == "Selected")
        #expect(tick.isButton)
        #expect(tick.frame.height >= TapTarget.minimum - 0.5, "\(tick)")
    }

    /// The values are read in full as one stop: "Description. Yours: …. Proposed from Storefront: …."
    @Test func theValuesAreOneStopReadInFull() async throws {
        let row = BookMatchMapping.field(Fixture.field(), viewerId: nil)
        let hosted = await HostedView(List { MatchFieldRowView(row: row, onTick: { _ in }, onChooseSource: { _ in }) })
        defer { hosted.close() }
        #expect(hosted.stop(labelled: row.valuesLabel) != nil, "\(hosted.tree)")
    }

    /// Long text shows three lines and Read all — a full target, not just its word.
    @Test func readAllIsAFullTarget() async throws {
        let long = String(repeating: "Ryland Grace is the sole survivor on a desperate mission. ", count: 4)
        let options = [FieldOptionUi(optionId: "o1", value: Fixture.text(long), sources: [Fixture.storefront])]
        let row = BookMatchMapping.field(Fixture.field(options: options), viewerId: nil)
        #expect(row.isLongText)
        let hosted = await HostedView(List { MatchFieldRowView(row: row, onTick: { _ in }, onChooseSource: { _ in }) })
        defer { hosted.close() }
        let readAll = try #require(hosted.stop(labelled: "Read all"), "\(hosted.tree)")
        #expect(readAll.frame.height >= TapTarget.minimum - 0.5, "\(readAll)")
    }

    // MARK: - Cover

    @Test func coverTilesAreARadioGroupThatSaysWhichIsChosen() async throws {
        let section = try #require(BookMatchMapping.cover(Fixture.cover()))
        let hosted = await HostedView(List { MatchCoverPicker(cover: section, bookId: nil, onChoose: { _ in }) })
        defer { hosted.close() }
        let chosen = try #require(hosted.stop(labelled: "Cover from Shelfdata, 1600 by 2400"), "\(hosted.tree)")
        #expect(chosen.isButton && chosen.isSelected)
        let other = try #require(hosted.stop(labelled: "Cover from Storefront, 2400 by 2400"), "\(hosted.tree)")
        #expect(!other.isSelected)
        let keep = try #require(hosted.stop(labelled: "Keep current cover"), "\(hosted.tree)")
        #expect(!keep.isSelected)
    }

    // MARK: - Chips

    /// The × names its object ("Remove Science Fiction"), a suggestion says where it came from, and both are
    /// full 44-point targets (iOS m15: chips were 30 pt).
    @Test func chipsNameTheirObjectAndAreFullTargets() async throws {
        let group = MatchLabelGroup(
            kind: .genres, title: "Genres",
            yours: [MatchYourLabel(label: "Science Fiction", removed: false)],
            suggested: [MatchSuggestion(
                label: "Thriller", sources: "Shelfdata", selected: true, accessibilityLabel: "Thriller, from Shelfdata"
            )]
        )
        let hosted = await HostedView(List { MatchLabelGroupView(group: group, actions: MatchReviewActions()) })
        defer { hosted.close() }
        let remove = try #require(hosted.stop(labelled: "Remove Science Fiction"), "\(hosted.tree)")
        let suggestion = try #require(hosted.stop(labelled: "Thriller, from Shelfdata"), "\(hosted.tree)")
        #expect(suggestion.value == "Selected")
        for chip in [remove, suggestion] {
            #expect(chip.isButton)
            #expect(chip.frame.height >= TapTarget.minimum - 0.5, "\(chip)")
        }
    }

    // MARK: - Apply tray (lesson M8)

    @Test func atAX5TheApplyTrayStaysUnderAQuarterOfTheScreen() async throws {
        let screen = CGSize(width: 393, height: 852)
        let bar = MatchApplyBar(summary: "5 fields · cover · 16 chapter names", canApply: true, applying: false, error: nil)
        let hosted = await HostedView(MatchApplyBarView(bar: bar, onApply: {}), size: screen, dynamicTypeSize: .accessibility5)
        defer { hosted.close() }
        let apply = try #require(hosted.stop(labelled: "Apply Changes"), "\(hosted.tree)")
        #expect(apply.hint == "5 fields · cover · 16 chapter names")
        let height = hosted.fittingHeight()
        #expect(height <= screen.height * 0.25, "tray is \(height) of \(screen.height)")
    }

    @Test func atDefaultSizesTheTrayShowsWhatApplyWillDo() async throws {
        let bar = MatchApplyBar(summary: "5 fields · cover · 16 chapter names", canApply: true, applying: false, error: nil)
        let hosted = await HostedView(MatchApplyBarView(bar: bar, onApply: {}))
        defer { hosted.close() }
        #expect(hosted.stop(labelled: "5 fields · cover · 16 chapter names") != nil, "\(hosted.tree)")
    }

    @Test func anApplyErrorIsShownAboveTheButton() async throws {
        let bar = MatchApplyBar(
            summary: "1 field", canApply: true, applying: false, error: "The server refused that. Nothing was changed."
        )
        let hosted = await HostedView(MatchApplyBarView(bar: bar, onApply: {}))
        defer { hosted.close() }
        let error = try #require(hosted.stops(labelContaining: "Nothing was changed.").first, "\(hosted.tree)")
        let apply = try #require(hosted.stop(labelled: "Apply Changes"), "\(hosted.tree)")
        #expect(error.frame.maxY <= apply.frame.minY + 1)
    }

    // MARK: - Receipt

    @Test func theReceiptOffersUndoAndSeeWhatChangedAsFullTargets() async throws {
        let receipt = MatchReceiptModel(
            id: "r1", sentence: "Changed 5 fields, cover from Shelfdata, 16 chapter names", changes: [],
            canUndo: true, undoing: false, undoError: nil
        )
        let hosted = await HostedView(
            MatchReceiptCapsule(phase: .shown(receipt), onUndo: {}, onSeeWhatChanged: {}, onDismiss: {})
        )
        defer { hosted.close() }
        #expect(hosted.stop(labelled: receipt.sentence) != nil, "\(hosted.tree)")
        for name in ["Undo", "See What Changed", "Dismiss"] {
            let button = try #require(hosted.stop(labelled: name), "\(name)\n\(hosted.tree)")
            #expect(button.isButton)
            #expect(button.frame.height >= TapTarget.minimum - 0.5, "\(button)")
        }
    }

    @Test func atAX5TheReceiptStillOffersUndo() async throws {
        let receipt = MatchReceiptModel(
            id: "r1", sentence: "Changed 5 fields, cover from Shelfdata, 16 chapter names", changes: [],
            canUndo: true, undoing: false, undoError: nil
        )
        let hosted = await HostedView(
            MatchReceiptCapsule(phase: .shown(receipt), onUndo: {}, onSeeWhatChanged: {}, onDismiss: {}),
            dynamicTypeSize: .accessibility5
        )
        defer { hosted.close() }
        let undo = try #require(hosted.stop(labelled: "Undo"), "\(hosted.tree)")
        #expect(undo.frame.maxX <= 393 + 0.5, "\(undo)")
    }
}
