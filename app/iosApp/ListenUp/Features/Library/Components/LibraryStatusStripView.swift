import SwiftUI

/// The Library's status strip, pinned above the Books grid: what the server is scanning, or that the library
/// is still arriving. One VoiceOver element that updates frequently, announced once a whole percent.
struct LibraryStatusStripView: View {
    let model: LibraryStatusStripModel

    var body: some View {
        VStack(alignment: .leading, spacing: Spacing.xxs) {
            Text(model.title)
                .font(.subheadline.weight(.semibold))
            Text(model.detail)
                .font(.footnote)
                .foregroundStyle(.secondary)
            if let fraction = model.fraction {
                ProgressView(value: fraction)
                    .tint(Color.listenUpOrange)
            } else {
                ProgressView()
                    .progressViewStyle(.linear)
                    .tint(Color.listenUpOrange)
            }
        }
        .padding(Spacing.s)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Color(.secondarySystemBackground), in: RoundedRectangle(cornerRadius: Radius.l, style: .continuous))
        .accessibilityElement(children: .combine)
        .accessibilityAddTraits(.updatesFrequently)
        .onChange(of: wholePercent) { _, _ in
            VoiceOverAnnouncement.post(model.detail)
        }
    }

    /// The scan's whole percent, so the strip is announced once a percent rather than once a book.
    private var wholePercent: Int? {
        model.fraction.map { Int($0 * 100) }
    }
}
