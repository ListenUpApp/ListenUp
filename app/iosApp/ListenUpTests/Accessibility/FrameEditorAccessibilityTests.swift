import SwiftUI
import Testing
import UIKit
@testable import ListenUp

/// What VoiceOver finds in Move and Scale: one adjustable stage with the named moves, the toolbar in HIG
/// order, a labelled zoom slider, and the could-not-open state.
@MainActor
@Suite("Move and Scale accessibility", .serialized)
struct FrameEditorAccessibilityTests {
    private static func photo() -> UIImage {
        UIGraphicsImageRenderer(size: CGSize(width: 60, height: 80)).image { context in
            UIColor.gray.setFill()
            context.fill(CGRect(x: 0, y: 0, width: 60, height: 80))
        }
    }

    private func hosted(_ state: FrameEditorView.Source, confirm: FrameEditorView.Confirm = .done) async -> HostedView {
        await HostedView(
            FrameEditorView(
                state: state,
                initial: nil,
                confirm: confirm,
                onConfirm: { _ in },
                onCancel: {},
                onChooseAnother: {}
            )
        )
    }

    @Test("the stage is one adjustable element named Photo position, with the move actions")
    func stageIsAdjustable() async throws {
        let view = await hosted(.ready(Self.photo()))
        defer { view.close() }
        let stage = try #require(view.stop(labelled: "Photo position"), "\(view.tree)")
        #expect(stage.traits.contains(.adjustable))
        #expect(stage.value == "Zoom 1.0×")
        #expect(stage.customActionNames == [
            "Show more of the top", "Show more of the bottom", "Show more of the left", "Show more of the right",
            "Reset"
        ])
    }

    @Test("Cancel leads and Done trails, per the HIG Sheets page")
    func toolbarOrder() async throws {
        let view = await hosted(.ready(Self.photo()))
        defer { view.close() }
        let cancel = try #require(view.stop(labelled: "Cancel"), "\(view.tree)")
        let done = try #require(view.stop(labelled: "Done"), "\(view.tree)")
        #expect(cancel.frame.minX < done.frame.minX)
    }

    @Test("from a menu the confirm reads Save")
    func saveFromMenu() async throws {
        let view = await hosted(.ready(Self.photo()), confirm: .save)
        defer { view.close() }
        #expect(view.stop(labelled: "Save") != nil, "\(view.tree)")
        #expect(view.stop(labelled: "Done") == nil)
    }

    @Test("the zoom slider says what it zooms and how far")
    func sliderLabelled() async throws {
        let view = await hosted(.ready(Self.photo()))
        defer { view.close() }
        let slider = try #require(view.stop(labelled: "Zoom"), "\(view.tree)")
        #expect(slider.value.contains("×"))
    }

    @Test("a photo that can't be opened says so, offers another, and dims the confirm")
    func couldNotOpen() async throws {
        let view = await hosted(.couldNotOpen)
        defer { view.close() }
        #expect(view.stop(labelled: "Couldn't open this photo") != nil, "\(view.tree)")
        let another = try #require(view.stop(labelled: "Choose Another"), "\(view.tree)")
        #expect(another.isButton)
        let done = try #require(view.stop(labelled: "Done"), "\(view.tree)")
        #expect(done.traits.contains(.notEnabled))
        #expect(view.stop(labelled: "Photo position") == nil)
    }
}
