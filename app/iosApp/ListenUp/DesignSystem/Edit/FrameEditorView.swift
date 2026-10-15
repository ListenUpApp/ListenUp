import SwiftUI
import UIKit

/// Move and Scale: the photo fills a black stage behind a dimmed scrim with a circle cut-out (Q13), modelled
/// on Contacts. Drag moves, pinch or the slider zooms, Reset returns to the centre crop, and VoiceOver gets an
/// adjustable zoom plus named moves. A full-screen cover on iPhone and a page sheet on iPad (`frameEditor`),
/// per the HIG Sheets and Modality pages: Cancel leads the toolbar and the confirm trails it as the prominent action.
struct FrameEditorView: View {
    /// What the editor is showing.
    enum Source {
        /// The photo is still being fetched.
        case loading
        /// The decoded photo, ready to frame.
        case ready(UIImage)
        /// The photo could not be decoded or fetched.
        case couldNotOpen
    }

    /// What the confirm does (Q2): inside a form it stages the frame, from a menu it saves it.
    enum Confirm {
        case done
        case save

        var title: String {
            switch self {
            case .done: String(localized: "common.done")
            case .save: String(localized: "common.save")
            }
        }
    }

    let state: Source
    let initial: ImageFrameValue?
    let confirm: Confirm
    let onConfirm: (ImageFrameValue) -> Void
    let onCancel: () -> Void
    let onChooseAnother: () -> Void

    var body: some View {
        NavigationStack {
            content
                .frame(maxWidth: .infinity, maxHeight: .infinity)
                .background(Color.black)
                .navigationTitle(String(localized: "edit.frame_title_ios"))
                .navigationBarTitleDisplayMode(.inline)
                .toolbarColorScheme(.dark, for: .navigationBar)
                .toolbar {
                    ToolbarItem(placement: .cancellationAction) {
                        Button(String(localized: "common.cancel"), action: onCancel)
                    }
                }
        }
        .preferredColorScheme(.dark)
    }

    @ViewBuilder private var content: some View {
        switch state {
        case .loading:
            ProgressView()
                .tint(.white)
                .toolbar { disabledConfirm }
        case .couldNotOpen:
            FrameEditorCouldNotOpen(onChooseAnother: onChooseAnother)
                .toolbar { disabledConfirm }
        case .ready(let image):
            FrameEditorStage(image: image, initial: initial, confirmTitle: confirm.title, onConfirm: onConfirm)
        }
    }

    /// The confirm, dimmed: there is nothing to frame yet, or nothing that can be.
    private var disabledConfirm: some ToolbarContent {
        ToolbarItem(placement: .confirmationAction) {
            Button(confirm.title) {}
                .frameEditorConfirmStyle()
                .disabled(true)
        }
    }
}

extension View {
    /// The confirm as the sheet's one prominent action (HIG, Buttons: "use a button that has a prominent visual
    /// style for the most likely action in a view"): accent-tinted glass, labelled in the on-coral ink so it
    /// reads at 4.5:1 — not the neutral capsule Cancel wears.
    fileprivate func frameEditorConfirmStyle() -> some View {
        buttonStyle(.glassProminent)
            .tint(.luTint)
            .onBrandFillLabel()
    }
}

// MARK: - Stage

/// The photo, the scrim, the cut-out and the gestures, over one `FrameEditorModel`. Its own view so the model
/// is created once per photo, when the photo arrives.
private struct FrameEditorStage: View {
    let image: UIImage
    let confirmTitle: String
    let onConfirm: (ImageFrameValue) -> Void

    @State private var model: FrameEditorModel
    @State private var lastDrag: CGSize = .zero
    @State private var lastMagnification: CGFloat = 1
    @Environment(\.horizontalSizeClass) private var sizeClass

    init(
        image: UIImage,
        initial: ImageFrameValue?,
        confirmTitle: String,
        onConfirm: @escaping (ImageFrameValue) -> Void
    ) {
        self.image = image
        self.confirmTitle = confirmTitle
        self.onConfirm = onConfirm
        _model = State(initialValue: FrameEditorModel(imageSize: image.size, initial: initial))
    }

    var body: some View {
        GeometryReader { proxy in
            let cut = Self.cutOut(in: proxy.size, inset: sizeClass == .regular ? 40 : 25)
            stage(size: proxy.size, cut: cut)
                .onAppear { model.setViewport(cut.width) }
                .onChange(of: cut.width) { _, side in model.setViewport(side) }
        }
        .haptic(.selectionTick, trigger: model.edgeTicks)
        .toolbar {
            ToolbarItem(placement: .confirmationAction) {
                Button(confirmTitle) { onConfirm(model.confirmedFrame()) }
                    .frameEditorConfirmStyle()
            }
        }
        .safeAreaBar(edge: .bottom) { controls }
    }

    /// The circle the photo is framed in: the largest that fits with `inset` to spare, centred.
    static func cutOut(in size: CGSize, inset: CGFloat) -> CGRect {
        let side = max(1, min(size.width, size.height) - inset * 2)
        return CGRect(x: (size.width - side) / 2, y: (size.height - side) / 2, width: side, height: side)
    }

    private func stage(size: CGSize, cut: CGRect) -> some View {
        let rect = FramedImageGeometry.rect(imageSize: model.imageSize, box: cut.size, frame: model.frame)
        return ZStack(alignment: .topLeading) {
            Image(uiImage: image)
                .resizable()
                .frame(width: rect.width, height: rect.height)
                .offset(x: cut.minX + rect.minX, y: cut.minY + rect.minY)
            // The fixed black scrim and white outline read the same in both themes: a photo is judged against
            // neutral black, as in Photos and Contacts.
            Path { path in
                path.addRect(CGRect(origin: .zero, size: size))
                path.addEllipse(in: cut)
            }
            .fill(Color.black.opacity(0.55), style: FillStyle(eoFill: true))
            Circle()
                .stroke(Color.white, lineWidth: 2)
                .frame(width: cut.width, height: cut.height)
                .offset(x: cut.minX, y: cut.minY)
        }
        .frame(width: size.width, height: size.height, alignment: .topLeading)
        .clipped()
        .contentShape(Rectangle())
        .gesture(dragGesture.simultaneously(with: magnifyGesture(cut: cut)))
        .accessibilityElement()
        .accessibilityLabel(String(localized: "edit.frame_photo_position"))
        .accessibilityValue(String(format: String(localized: "edit.frame_zoom_value"), model.zoomText))
        .accessibilityHint(String(localized: "edit.frame_hint_ios"))
        .accessibilityAdjustableAction { direction in
            switch direction {
            case .increment: model.increment()
            case .decrement: model.decrement()
            @unknown default: break
            }
        }
        // SwiftUI's rotor offers the last-written action first, so these are written bottom-up to read top, bottom,
        // left, right, then Reset.
        .accessibilityAction(named: Text(String(localized: "edit.frame_reset"))) { model.reset() }
        .accessibilityAction(named: Text(String(localized: "edit.frame_show_right"))) { model.nudge(.right) }
        .accessibilityAction(named: Text(String(localized: "edit.frame_show_left"))) { model.nudge(.left) }
        .accessibilityAction(named: Text(String(localized: "edit.frame_show_bottom"))) { model.nudge(.down) }
        .accessibilityAction(named: Text(String(localized: "edit.frame_show_top"))) { model.nudge(.up) }
    }

    /// The photo follows the finger, one step per change.
    private var dragGesture: some Gesture {
        DragGesture(minimumDistance: 0)
            .onChanged { value in
                let delta = CGSize(
                    width: value.translation.width - lastDrag.width,
                    height: value.translation.height - lastDrag.height
                )
                lastDrag = value.translation
                model.pan(by: delta)
            }
            .onEnded { _ in lastDrag = .zero }
    }

    /// Pinch zooms about the point between the fingers, in the cut-out's coordinates.
    private func magnifyGesture(cut: CGRect) -> some Gesture {
        MagnifyGesture()
            .onChanged { value in
                let factor = value.magnification / lastMagnification
                lastMagnification = value.magnification
                let anchor = CGPoint(x: value.startLocation.x - cut.minX, y: value.startLocation.y - cut.minY)
                model.zoom(by: factor, anchor: anchor)
            }
            .onEnded { _ in lastMagnification = 1 }
    }

    /// The zoom slider across the full width, Reset centred on its own row below (HIG, Sliders: a fine control
    /// for a 4× range wants a long track), in a system safe-area bar (no hand-rolled glass, iosApp rule 11).
    private var controls: some View {
        VStack(spacing: Spacing.xs) {
            Slider(
                value: Binding(get: { model.sliderPosition }, set: { model.sliderPosition = $0 }),
                in: 0...1
            ) {
                Text(String(localized: "edit.frame_zoom"))
            } minimumValueLabel: {
                Image(systemName: "minus.magnifyingglass").accessibilityHidden(true)
            } maximumValueLabel: {
                Image(systemName: "plus.magnifyingglass").accessibilityHidden(true)
            }
            .accessibilityValue(model.zoomText)
            Button(String(localized: "edit.frame_reset")) { model.reset() }
                .disabled(model.isCentered)
                .frame(minHeight: 44)
        }
        .padding(.horizontal, Spacing.l)
        .padding(.top, Spacing.m)
        .padding(.bottom, Spacing.xs)
        .frame(maxWidth: .infinity)
    }
}

// MARK: - Presentation

extension View {
    /// Presents Move and Scale for `item`: a full-screen cover at a compact width, a page sheet at a regular
    /// one (HIG Sheets: "Prefer using the page or form sheet presentation styles in an iPadOS app").
    /// `onDismiss` runs once the editor has gone, so a follow-up presentation (the photo picker, after
    /// Choose Another) does not collide with it.
    func frameEditor<Item: Identifiable, Editor: View>(
        item: Binding<Item?>,
        onDismiss: (() -> Void)? = nil,
        @ViewBuilder content: @escaping (Item) -> Editor
    ) -> some View {
        modifier(FrameEditorPresentation(item: item, onDismiss: onDismiss, editor: content))
    }
}

private struct FrameEditorPresentation<Item: Identifiable, Editor: View>: ViewModifier {
    @Binding var item: Item?
    let onDismiss: (() -> Void)?
    let editor: (Item) -> Editor
    @Environment(\.horizontalSizeClass) private var sizeClass

    func body(content: Content) -> some View {
        if sizeClass == .regular {
            content.sheet(item: $item, onDismiss: onDismiss) { editor($0).presentationSizing(.page) }
        } else {
            content.fullScreenCover(item: $item, onDismiss: onDismiss) { editor($0) }
        }
    }
}
