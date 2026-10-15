import PhotosUI
import SwiftUI
import UIKit

/// How an `ImageEditHeader` frames the photos it edits (avatars and contributor photos): a pick opens Move
/// and Scale before it is handed up, a pick that can't be opened says so there instead of vanishing, and the
/// photo already shown gets Adjust Photo. Covers have no framing.
struct PhotoFraming {
    /// A new pick, framed: its upload-ready bytes and how they sit in the circle. Called on Done.
    let onFramed: (Data, ImageFrameValue) -> Void
    /// The photo already shown, for Adjust Photo; nil hides the button (there is no photo to adjust).
    let adjust: Adjust?

    /// Adjust Photo for the photo the header already shows.
    struct Adjust {
        /// The frame it is shown with now; nil is the centre crop.
        let initial: ImageFrameValue?
        /// Fetches the photo for the editor; nil when it can't be had.
        let photo: @MainActor () async -> UIImage?
        /// The new frame, on Done.
        let onAdjusted: (ImageFrameValue) -> Void
    }
}

/// An editable image (cover or avatar) with a camera badge that opens a `PhotosPicker`,
/// plus an optional remove affordance. Emits the picked image as `Data`, or, with `framing`,
/// framed through Move and Scale (see `PhotoFraming`).
struct ImageEditHeader<ImageContent: View>: View {
    enum Shape { case circle, rounded }

    let shape: Shape
    let size: CGFloat
    let isUploading: Bool
    let canRemove: Bool
    let onPicked: (Data) -> Void
    let onRemove: () -> Void
    var framing: PhotoFraming?
    @ViewBuilder var image: () -> ImageContent

    @State private var item: PhotosPickerItem?
    @State private var pickTask: Task<Void, Never>?
    @State private var showsPicker = false
    /// A pick waiting in Move and Scale.
    @State private var pendingPick: PendingPick?
    /// Adjust Photo is open on the photo already shown.
    @State private var adjusting: AdjustSession?
    /// Choose Another was tapped: reopen the picker once the editor has gone.
    @State private var reopensPicker = false

    var body: some View {
        VStack(spacing: 10) {
            ZStack(alignment: .bottomTrailing) {
                image()
                    .frame(width: size, height: size)
                    .clipShape(clip)
                    .overlay(clip.stroke(Color.luSeparator, lineWidth: 0.5))
                    .overlay {
                        if isUploading {
                            ProgressView()
                                .tint(.white)
                                .frame(width: size, height: size)
                                .background(.black.opacity(0.25), in: clip)
                                .accessibilityLabel(Text("edit.uploading_image"))
                        }
                    }

                Button {
                    showsPicker = true
                } label: {
                    // Dynamic Type exclusion: fixed-box glyph in a 34×34 camera badge circle
                    Image(systemName: "camera.fill")
                        .font(.system(size: 14, weight: .semibold)) // decorative fixed size
                        .foregroundStyle(Color.luOnTint)
                        .frame(width: 34, height: 34)
                        .background(Circle().fill(Color.luTint))
                        .overlay(Circle().stroke(Color.luSurface, lineWidth: 2.5))
                }
                .buttonStyle(.plain)
                .accessibilityLabel(Text("edit.change_image"))
                .offset(x: 6, y: 6)
            }

            actions
        }
        .photosPicker(isPresented: $showsPicker, selection: $item, matching: .images)
        .onChange(of: item) { _, newItem in
            // A newer pick supersedes a decode still in flight: its result is never delivered.
            pickTask?.cancel()
            guard let newItem else { return }
            pickTask = Task {
                // Normalize to JPEG before handing bytes up. `loadTransferable(type: Data.self)`
                // returns the photo's ORIGINAL encoding, which on iPhone is usually HEIC — a format
                // the server's image validator rejects (it sniffs for JPEG/PNG/WebP magic bytes only)
                // → 422 on upload. Decoding and re-encoding as JPEG guarantees a format the server
                // accepts, for every consumer of this shared header (avatar, contributor, cover).
                // The size is bounded (2,048 px, parity with Android) so a huge photo is never decoded
                // at full size.
                let jpeg = await Self.loadNormalizedJPEG(from: newItem)
                if !Task.isCancelled {
                    if framing != nil {
                        // Framed photos go through Move and Scale; one that can't be opened says so there.
                        pendingPick = PendingPick(data: jpeg)
                    } else if let jpeg {
                        onPicked(jpeg)
                    }
                }
                if !Task.isCancelled { item = nil }
            }
        }
        .frameEditor(item: $pendingPick, onDismiss: reopenPickerIfAsked) { pick in
            FrameEditorView(
                state: pick.source,
                initial: nil,
                confirm: .done,
                onConfirm: { frame in
                    if let data = pick.data { framing?.onFramed(data, frame) }
                    pendingPick = nil
                },
                onCancel: { pendingPick = nil },
                onChooseAnother: {
                    reopensPicker = true
                    pendingPick = nil
                }
            )
        }
        .frameEditor(item: $adjusting, onDismiss: reopenPickerIfAsked) { _ in
            if let adjust = framing?.adjust {
                FetchedPhotoFrameEditor(
                    fetch: adjust.photo,
                    initial: adjust.initial,
                    confirm: .done,
                    onConfirm: { frame in
                        adjust.onAdjusted(frame)
                        adjusting = nil
                    },
                    onCancel: { adjusting = nil },
                    onChooseAnother: {
                        reopensPicker = true
                        adjusting = nil
                    }
                )
            }
        }
    }

    /// Adjust Photo beside Remove Photo, under the image.
    @ViewBuilder private var actions: some View {
        let showsAdjust = framing?.adjust != nil
        if canRemove || showsAdjust {
            HStack(spacing: 20) {
                if showsAdjust {
                    Button(String(localized: "edit.adjust_photo_ios")) { adjusting = AdjustSession() }
                        .font(.subheadline)
                        .buttonStyle(.plain)
                        .foregroundStyle(Color.luTint)
                }
                if canRemove {
                    Button(role: .destructive) { onRemove() } label: {
                        Text(String(localized: "edit.remove_image")).font(.subheadline)
                    }
                    .buttonStyle(.plain)
                    .foregroundStyle(Color.luTint)
                }
            }
        }
    }

    private func reopenPickerIfAsked() {
        guard reopensPicker else { return }
        reopensPicker = false
        showsPicker = true
    }

    /// `@concurrent` ⇒ the photo load, decode and JPEG encode run on the cooperative pool, never the
    /// main actor.
    @concurrent
    private nonisolated static func loadNormalizedJPEG(from item: PhotosPickerItem) async -> Data? {
        guard let data = try? await item.loadTransferable(type: Data.self) else { return nil }
        return PickedImage.normalizedJPEG(from: data)
    }

    private var clip: AnyShape {
        shape == .circle
            ? AnyShape(Circle())
            : AnyShape(RoundedRectangle(cornerRadius: Radius.l, style: .continuous))
    }
}

/// One opening of Adjust Photo.
private struct AdjustSession: Identifiable {
    let id = UUID()
}
