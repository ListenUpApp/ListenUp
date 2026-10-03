import SwiftUI

/// A settings row that pairs a leading ``IconTile`` and a title / optional subtitle with a system
/// `Toggle`. It is a `List`/`Form` row: the list supplies the insets, the separator and the grouped
/// surface, and the `Toggle` supplies the label-to-switch pairing VoiceOver reads as one control.
/// HIG, Toggles: "In iOS … use a switch in a list row."
///
/// While `isBusy` is true the switch is *replaced* by a spinner (not merely disabled) — the
/// load-bearing guard against a second flip landing before the in-flight write resolves. A
/// `.disabled` row dims the way every system switch does.
struct ToggleRow: View {
    let systemImage: String
    /// A meaningful colour for the leading tile; `nil` (the default) keeps it neutral — see `IconTile`.
    var tint: Color?
    let title: String
    var subtitle: String?
    @Binding var isOn: Bool
    var isBusy: Bool = false
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize

    /// The tile is decorative (and hidden from VoiceOver). At the accessibility sizes it took ~45 points
    /// from a column narrow enough to hyphenate "Hardcov-er", so it steps aside there. HIG, Typography.
    static func showsIconTile(at size: DynamicTypeSize) -> Bool { !size.isAccessibilitySize }

    var body: some View {
        if isBusy {
            LabeledContent {
                ProgressView()
            } label: {
                label
            }
            .accessibilityElement(children: .combine)
        } else {
            Toggle(isOn: $isOn) { label }
        }
    }

    private var label: some View {
        HStack(spacing: 13) {
            if Self.showsIconTile(at: dynamicTypeSize) {
                IconTile(systemImage: systemImage, tint: tint)
                    .accessibilityHidden(true)
            }
            VStack(alignment: .leading, spacing: 1) {
                Text(title)
                    .font(.body)
                    .foregroundStyle(.primary)
                if let subtitle {
                    Text(subtitle)
                        .font(.footnote)
                        .foregroundStyle(Color.secondary)
                }
            }
        }
    }
}

#Preview("ToggleRow") {
    @Previewable @State var open = false
    return Form {
        ToggleRow(
            systemImage: "person.badge.plus",
            tint: .green,
            title: "Open registration",
            subtitle: "Allow anyone to request an account",
            isOn: $open
        )
        ToggleRow(
            systemImage: "person.badge.plus",
            tint: .green,
            title: "Saving…",
            subtitle: "In flight",
            isOn: $open,
            isBusy: true
        )
    }
}
