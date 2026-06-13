import SwiftUI
import PhotosUI

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
                        }
                    }

                PhotosPicker(selection: $item, matching: .images) {
                    Image(systemName: "camera.fill")
                        .font(.system(size: 14, weight: .semibold))
                        .foregroundStyle(Color.luOnTint)
                        .frame(width: 34, height: 34)
                        .background(Circle().fill(Color.luTint))
                        .overlay(Circle().stroke(Color.luSurface, lineWidth: 2.5))
                }
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
                if let data = try? await newItem.loadTransferable(type: Data.self) {
                    onPicked(data)
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
