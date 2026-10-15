import Foundation
import SwiftUI
import UIKit

/// A photo just picked for framing: the upload-ready bytes, decoded once for Move and Scale. Nil bytes (the
/// picker's image could not be normalised) or bytes that will not decode open the editor's "Couldn't open
/// this photo" state rather than being dropped in silence.
struct PendingPick: Identifiable {
    let id = UUID()
    let data: Data?
    let image: UIImage?

    init(data: Data?) {
        self.data = data
        self.image = data.flatMap { UIImage(data: $0) }
    }

    /// What Move and Scale shows for this pick.
    var source: FrameEditorView.Source {
        image.map { .ready($0) } ?? .couldNotOpen
    }
}

/// Move and Scale over a photo that has to be fetched first (Adjust Photo on a saved photo): a spinner until
/// it arrives, and "Couldn't open this photo" if it never does.
struct FetchedPhotoFrameEditor: View {
    let fetch: @MainActor () async -> UIImage?
    let initial: ImageFrameValue?
    let confirm: FrameEditorView.Confirm
    let onConfirm: (ImageFrameValue) -> Void
    let onCancel: () -> Void
    let onChooseAnother: () -> Void

    @State private var source: FrameEditorView.Source = .loading

    var body: some View {
        FrameEditorView(
            state: source,
            initial: initial,
            confirm: confirm,
            onConfirm: onConfirm,
            onCancel: onCancel,
            onChooseAnother: onChooseAnother
        )
        .task {
            let image = await fetch()
            source = image.map { .ready($0) } ?? .couldNotOpen
        }
    }
}
