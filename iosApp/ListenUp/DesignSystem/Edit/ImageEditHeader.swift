import SwiftUI
import PhotosUI
import UIKit

/// An editable image (cover or avatar) with a camera badge that opens a `PhotosPicker`,
/// plus an optional remove affordance. Emits the picked image as `Data`.
struct ImageEditHeader<ImageContent: View>: View {
    enum Shape { case circle, rounded }

    let shape: Shape
    let size: CGFloat
    let isUploading: Bool
    let canRemove: Bool
    let onPicked: (Data) -> Void
    let onRemove: () -> Void
    @ViewBuilder var image: () -> ImageContent

    @State private var item: PhotosPickerItem?

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

                PhotosPicker(selection: $item, matching: .images) {
                    // Dynamic Type exclusion: fixed-box glyph in a 34×34 camera badge circle
                    Image(systemName: "camera.fill")
                        .font(.system(size: 14, weight: .semibold))
                        .foregroundStyle(Color.luOnTint)
                        .frame(width: 34, height: 34)
                        .background(Circle().fill(Color.luTint))
                        .overlay(Circle().stroke(Color.luSurface, lineWidth: 2.5))
                }
                .accessibilityLabel(Text("edit.change_image"))
                .offset(x: 6, y: 6)
            }

            if canRemove {
                Button(role: .destructive) { onRemove() } label: {
                    Text(String(localized: "edit.remove_image")).font(.subheadline)
                }
                .buttonStyle(.plain)
                .foregroundStyle(Color.luTint)
            }
        }
        .onChange(of: item) { _, newItem in
            guard let newItem else { return }
            Task {
                // Normalize to JPEG before handing bytes up. `loadTransferable(type: Data.self)`
                // returns the photo's ORIGINAL encoding, which on iPhone is usually HEIC — a format
                // the server's image validator rejects (it sniffs for JPEG/PNG/WebP magic bytes only)
                // → 422 on upload. Decoding to UIImage and re-encoding as JPEG guarantees a format the
                // server accepts, for every consumer of this shared header (avatar, contributor, cover).
                if let data = try? await newItem.loadTransferable(type: Data.self),
                   let jpeg = UIImage(data: data)?.jpegData(compressionQuality: 0.9) {
                    onPicked(jpeg)
                }
                item = nil
            }
        }
    }

    private var clip: AnyShape {
        shape == .circle
            ? AnyShape(Circle())
            : AnyShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
    }
}
