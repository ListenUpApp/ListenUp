import SwiftUI

/// What Move and Scale shows when the photo can't be decoded or fetched (errors board, iOS column): the
/// reason, what would work, and a way to pick another. Replaces the silent drop a bad pick used to get.
struct FrameEditorCouldNotOpen: View {
    let onChooseAnother: () -> Void

    var body: some View {
        VStack(spacing: 12) {
            Image(systemName: "photo.badge.exclamationmark")
                .font(.largeTitle)
                .accessibilityHidden(true)
            Text(String(localized: "edit.frame_could_not_open"))
                .font(.title3.weight(.semibold))
                .multilineTextAlignment(.center)
            Text(String(localized: "edit.frame_could_not_open_body"))
                .font(.subheadline)
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
            Button(String(localized: "edit.frame_choose_another_ios"), action: onChooseAnother)
                .buttonStyle(.borderedProminent)
                .controlSize(.large)
                .padding(.top, Spacing.xs)
        }
        .foregroundStyle(.white)
        .padding(Spacing.l)
        .accessibilityElement(children: .contain)
        .onAppear { VoiceOverAnnouncement.post(String(localized: "edit.frame_could_not_open")) }
    }
}
