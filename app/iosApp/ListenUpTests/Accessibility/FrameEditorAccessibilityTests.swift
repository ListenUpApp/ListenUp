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

    @Test("Done is the prominent action: filled with the accent, where Cancel is not")
    func confirmIsProminent() async throws {
        let view = await hosted(.ready(Self.photo()))
        defer { view.close() }
        let cancel = try #require(view.stop(labelled: "Cancel"), "\(view.tree)")
        let done = try #require(view.stop(labelled: "Done"), "\(view.tree)")
        // Composited, so the toolbar's glass is in the capture.
        let drawn = DrawnContrast(view, composited: true)
        let doneFill = drawn.background(in: done.frame.insetBy(dx: 2, dy: 2))
        let cancelFill = drawn.background(in: cancel.frame.insetBy(dx: 2, dy: 2))
        #expect(doneFill.red - doneFill.blue > 0.3, "Done drew \(doneFill), not the coral accent")
        #expect(cancelFill.red - cancelFill.blue < 0.1, "Cancel drew \(cancelFill), a second prominent action")
    }

    @Test("the zoom slider spans the width, with Reset centred on its own row below")
    func sliderSpansTheWidth() async throws {
        let view = await hosted(.ready(Self.photo()))
        defer { view.close() }
        // The room inside the safe area: the closed iPhone Duo keeps a strip down one edge for its vertical bar.
        let usable = view.usableBounds
        let slider = try #require(view.stop(labelled: "Zoom"), "\(view.tree)")
        let reset = try #require(view.stop(labelled: "Reset"), "\(view.tree)")
        // Inside ~20 pt margins and the two magnifier glyphs, the track keeps the rest of the width.
        #expect(slider.frame.width >= usable.width - 120, "slider \(slider.frame) in a \(usable) usable area")
        #expect(reset.frame.minY >= slider.frame.maxY)
        #expect(abs(reset.frame.midX - usable.midX) < 2)
    }

    @Test("on iPad the zoom slider keeps at least 300 pt")
    func sliderOnIPad() async throws {
        let view = await HostedView(
            FrameEditorView(
                state: .ready(Self.photo()), initial: nil, confirm: .done,
                onConfirm: { _ in }, onCancel: {}, onChooseAnother: {}
            )
            .environment(\.horizontalSizeClass, .regular),
            size: CGSize(width: 700, height: 740)
        )
        defer { view.close() }
        let slider = try #require(view.stop(labelled: "Zoom"), "\(view.tree)")
        #expect(slider.frame.width >= 300, "slider \(slider.frame)")
    }

    @Test("Choose Another's label reads at 4.5:1 on its fill")
    func chooseAnotherContrast() async throws {
        let view = await hosted(.couldNotOpen)
        defer { view.close() }
        let another = try #require(view.stop(labelled: "Choose Another"), "\(view.tree)")
        // Clear of the capsule's rounded ends, where the black page behind it would pass for ink.
        let label = another.frame.insetBy(dx: another.frame.height / 2, dy: 6)
        // Composited: the prominent fill is drawn by the render server, which a plain layer capture misses.
        let ink = DrawnContrast(view, composited: true).strongestInk(in: label)
        #expect(ink >= 4.5, "Choose Another reads at \(ink):1")
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
