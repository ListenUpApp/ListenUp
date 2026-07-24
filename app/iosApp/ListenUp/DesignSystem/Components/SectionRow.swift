import SwiftUI

/// A section header: a bold title with an optional trailing "See All" action.
/// The action slot is unused in C1 (carousels show up to 10) but kept for later.
struct SectionRow: View {
    let title: String
    var actionTitle: String?
    var onAction: (() -> Void)?

    var body: some View {
        HStack(alignment: .firstTextBaseline) {
            Text(title)
                .font(.title2.bold())
                .foregroundStyle(.primary)
            Spacer()
            if let actionTitle, let onAction {
                Button(action: onAction) {
                    HStack(spacing: 2) {
                        Text(actionTitle)
                        Image(systemName: "chevron.right").font(.caption.weight(.semibold))
                    }
                    .font(.subheadline)
                    .foregroundStyle(Color.luTint)
                }
                .buttonStyle(.plain)
            }
        }
    }
}

#Preview("SectionRow") {
    VStack(spacing: 24) {
        SectionRow(title: "Written by")
        SectionRow(title: "Series", actionTitle: "See All", onAction: {})
    }
    .padding()
    .frame(maxWidth: .infinity, maxHeight: .infinity)
    .background(Color.luSurface)
}
