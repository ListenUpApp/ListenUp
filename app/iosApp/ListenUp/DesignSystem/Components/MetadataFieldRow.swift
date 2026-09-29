import SwiftUI

/// A selectable `List` row that pairs a leading `IconTile`, a caption + value (or a custom
/// value view), an optional trailing thumbnail, and a trailing `CircularCheckToggle`. The whole
/// row dims when deselected. This is the metadata-match field idiom, but it is generic: any
/// "label / value / opt-in" row can compose it.
///
/// Two value forms: pass a `value` string for the common single-line case, or supply a `value`
/// view builder for rich content (clamped description, genre chips). Tapping the row or the
/// toggle both fire `onToggle`. It keeps a custom press style rather than the list's automatic
/// button style: a row whose value holds its own buttons (genre chips) must not become one
/// row-wide button that swallows theirs.
struct MetadataFieldRow<Value: View, Thumb: View>: View {
    let systemImage: String
    let label: String
    let isOn: Bool
    let onToggle: () -> Void
    @ViewBuilder var value: () -> Value
    @ViewBuilder var thumb: () -> Thumb

    var body: some View {
        Button(action: onToggle) {
            HStack(spacing: 13) {
                IconTile(systemImage: systemImage, isActive: isOn)

                VStack(alignment: .leading, spacing: 2) {
                    Text(label)
                        .font(.caption)
                        .foregroundStyle(Color.secondary)
                    value()
                }
                .frame(maxWidth: .infinity, alignment: .leading)

                thumb()

                CircularCheckMark(isOn: isOn)
            }
            .opacity(isOn ? 1 : 0.5)
            .contentShape(Rectangle())
        }
        .buttonStyle(PressScaleButtonStyle())
        .accessibilityElement(children: .combine)
        .accessibilityLabel(label)
        .accessibilityValue(isOn ? String(localized: "common.selected") : "")
        .accessibilityAddTraits(isOn ? .isSelected : [])
    }
}

// MARK: - Convenience initializers

extension MetadataFieldRow where Value == MetadataValueText, Thumb == EmptyView {
    /// Single-line value, no thumbnail — the common field case.
    init(systemImage: String, label: String, value: String, isOn: Bool, onToggle: @escaping () -> Void) {
        self.init(systemImage: systemImage, label: label, isOn: isOn, onToggle: onToggle) {
            MetadataValueText(value)
        } thumb: {
            EmptyView()
        }
    }
}

/// The standard single-line value rendering for a field row (up to three lines, primary color).
struct MetadataValueText: View {
    let value: String
    init(_ value: String) { self.value = value }
    var body: some View {
        Text(value)
            .font(.callout)
            .foregroundStyle(.primary)
            .lineLimit(3)
    }
}

extension MetadataFieldRow where Thumb == EmptyView {
    /// Custom value view, no thumbnail.
    init(
        systemImage: String,
        label: String,
        isOn: Bool,
        onToggle: @escaping () -> Void,
        @ViewBuilder value: @escaping () -> Value
    ) {
        self.init(systemImage: systemImage, label: label, isOn: isOn, onToggle: onToggle, value: value) {
            EmptyView()
        }
    }
}

#Preview("MetadataFieldRow") {
    struct Demo: View {
        @State private var coverOn = true
        @State private var narratorOn = false
        var body: some View {
            Form {
                MetadataFieldRow(
                    systemImage: "photo",
                    label: "Cover",
                    isOn: coverOn,
                    onToggle: { coverOn.toggle() }
                ) {
                    Text("New artwork from Audible").font(.callout)
                } thumb: {
                    RoundedRectangle(cornerRadius: Radius.s).fill(Color.luFill).frame(width: 36, height: 36)
                }
                MetadataFieldRow(
                    systemImage: "textformat",
                    label: "Title",
                    value: "The Primal Hunter 9: A LitRPG Adventure",
                    isOn: true,
                    onToggle: {}
                )
                MetadataFieldRow(
                    systemImage: "mic",
                    label: "Narrators",
                    value: "Travis Baldree",
                    isOn: narratorOn,
                    onToggle: { narratorOn.toggle() }
                )
            }
        }
    }
    return Demo()
}
