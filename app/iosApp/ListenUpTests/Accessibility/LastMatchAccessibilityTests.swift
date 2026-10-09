import Foundation
import SwiftUI
import Testing
@testable import ListenUp

/// Book Detail's last-match row for VoiceOver and large text: the sentence is read, both actions are buttons with
/// full 44-point targets, and at AX5 they stack inside the screen rather than run off it.
@MainActor
@Suite("Last match accessibility", .serialized)
struct LastMatchAccessibilityTests {
    private let model = LastMatchRowModel(
        receiptId: "r1",
        appliedAt: Date(),
        matchedBy: nil,
        receipt: MatchReceiptModel(
            id: "r1", sentence: "Changed 5 fields, cover from Shelfdata, 16 chapter names", changes: [],
            canUndo: true, undoing: false, undoError: nil
        ),
        showingChanges: false,
        undoing: false,
        undoError: nil
    )

    @Test func theRowReadsItsSentenceAndOffersBothActionsAsFullTargets() async throws {
        let hosted = await HostedView(LastMatchRow(model: model, onSeeWhatChanged: {}, onUndo: {}))
        defer { hosted.close() }
        #expect(hosted.stop(labelled: "Details matched just now") != nil, "\(hosted.tree)")
        for name in ["See What Changed", "Undo Last Match"] {
            let button = try #require(hosted.stop(labelled: name), "\(name)\n\(hosted.tree)")
            #expect(button.isButton)
            #expect(button.frame.height >= TapTarget.minimum - 0.5, "\(button)")
        }
    }

    @Test func atAX5BothActionsStayOnScreen() async throws {
        let hosted = await HostedView(
            LastMatchRow(model: model, onSeeWhatChanged: {}, onUndo: {}),
            dynamicTypeSize: .accessibility5
        )
        defer { hosted.close() }
        for name in ["See What Changed", "Undo Last Match"] {
            let button = try #require(hosted.stop(labelled: name), "\(name)\n\(hosted.tree)")
            #expect(button.frame.maxX <= 393 + 0.5, "\(button)")
        }
    }
}
