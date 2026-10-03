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
///
/// For VoiceOver a plain row is one stop — "Title, Selected" — because it is one thing. A row whose value
/// holds its own controls (`containsControls`, the genre and mood chips) is not: folded into one stop, its
/// chips were unreachable and a double-tap flipped them all. There the row is a container — the field's
/// check comes first as its own "turn them all on or off" control, then each chip as itself. HIG,
/// VoiceOver: combine elements only when they are one thing, and keep every control reachable.
struct MetadataFieldRow<Value: View, Thumb: View>: View {
    let systemImage: String
    let label: String
    let isOn: Bool
    var containsControls = false
    let onToggle: () -> Void
    @ViewBuilder var value: () -> Value
    @ViewBuilder var thumb: () -> Thumb
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize

    /// The leading tile is decorative; at the accessibility sizes it steps aside so the value keeps the
    /// width to wrap in (HIG, Typography: adjust the layout at large sizes).
    private var showsIconTile: Bool { !dynamicTypeSize.isAccessibilitySize }

    var body: some View {
        if containsControls {
            controlsRow
        } else {
            plainRow
        }
    }

    private var selectedValue: String { isOn ? String(localized: "common.selected") : "" }

    /// One thing, one stop: "Title, Selected".
    private var plainRow: some View {
        Button(action: onToggle) {
            HStack(spacing: 13) {
                if showsIconTile {
                    IconTile(systemImage: systemImage, isActive: isOn)
                }
                caption(label)
                thumb()
                CircularCheckMark(isOn: isOn)
            }
            .opacity(isOn ? 1 : 0.5)
            .contentShape(Rectangle())
        }
        .buttonStyle(PressScaleButtonStyle())
        .accessibilityElement(children: .combine)
        .accessibilityLabel(label)
        .accessibilityValue(selectedValue)
        .accessibilityAddTraits(isOn ? .isSelected : [])
    }

    /// Not a row-wide button: in a `List` that makes the whole cell one element and swallows the chips.
    /// A tap anywhere but a chip still turns them all on or off; for VoiceOver the check is that control.
    private var controlsRow: some View {
        HStack(spacing: 13) {
            if showsIconTile {
                IconTile(systemImage: systemImage, isActive: isOn)
                    .accessibilityHidden(true)
            }
            caption(label)
            thumb()
            Button(action: onToggle) {
                CircularCheckMark(isOn: isOn)
                    .frame(minWidth: TapTarget.minimum, minHeight: TapTarget.minimum)
                    .contentShape(Rectangle())
            }
            .buttonStyle(PressScaleButtonStyle())
            .accessibilityLabel(label)
            .accessibilityValue(selectedValue)
            .accessibilityHint(String(localized: "metadata.field_toggle_all_hint"))
            .accessibilityAddTraits(isOn ? .isSelected : [])
            .accessibilitySortPriority(1)
        }
        .opacity(isOn ? 1 : 0.5)
        .contentShape(Rectangle())
        .onTapGesture(perform: onToggle)
        .accessibilityElement(children: .contain)
    }

    private func caption(_ label: String) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(label)
                .font(.caption)
                .foregroundStyle(Color.secondary)
                // In a controls row the check carries the name; read twice it would only repeat.
                .accessibilityHidden(containsControls)
            value()
        }
        .frame(maxWidth: .infinity, alignment: .leading)
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
    /// Custom value view, no thumbnail. `containsControls` when the value holds its own buttons.
    init(
        systemImage: String,
        label: String,
        isOn: Bool,
        containsControls: Bool = false,
        onToggle: @escaping () -> Void,
        @ViewBuilder value: @escaping () -> Value
    ) {
        self.init(
            systemImage: systemImage, label: label, isOn: isOn, containsControls: containsControls,
            onToggle: onToggle, value: value
        ) {
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
