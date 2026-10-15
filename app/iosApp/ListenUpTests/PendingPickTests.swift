import Foundation
import Testing
import UIKit
@testable import ListenUp

/// A pick that can't be opened reaches Move and Scale's "Couldn't open this photo" instead of vanishing, as it
/// used to; a good one opens ready to frame.
@Suite("PendingPick")
struct PendingPickTests {
    private func isReady(_ source: FrameEditorView.Source) -> Bool {
        if case .ready = source { return true }
        return false
    }

    private func couldNotOpen(_ source: FrameEditorView.Source) -> Bool {
        if case .couldNotOpen = source { return true }
        return false
    }

    @Test func aDecodablePickOpensReadyToFrame() throws {
        let jpeg = try #require(
            UIGraphicsImageRenderer(size: CGSize(width: 30, height: 40)).image { context in
                UIColor.orange.setFill()
                context.fill(CGRect(x: 0, y: 0, width: 30, height: 40))
            }.jpegData(compressionQuality: 0.9)
        )
        let pick = PendingPick(data: jpeg)
        #expect(isReady(pick.source))
        #expect(pick.data == jpeg)
    }

    @Test func aPickThePickerCouldNotNormaliseCanNotBeOpened() {
        #expect(couldNotOpen(PendingPick(data: nil).source))
    }

    @Test func bytesThatAreNotAnImageCanNotBeOpened() {
        #expect(couldNotOpen(PendingPick(data: Data([0x00, 0x01, 0x02])).source))
    }

    @Test func everyPickIsItsOwnSession() {
        #expect(PendingPick(data: nil).id != PendingPick(data: nil).id)
    }
}
