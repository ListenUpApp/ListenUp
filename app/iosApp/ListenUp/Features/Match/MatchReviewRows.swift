import SwiftUI
import Shared

/// A tick circle that is a toggle to VoiceOver (HIG, Toggles): its own name, the toggle trait, and
/// Selected / Not selected as its value — the circle's fill is never the only tell.
struct MatchTick: View {
    let isOn: Bool
    let label: String
    let onChange: (Bool) -> Void

    var body: some View {
        Button {
            onChange(!isOn)
        } label: {
            CircularCheckMark(isOn: isOn)
                .minimumTapTarget(visualSize: 26)
        }
        .buttonStyle(.plain)
        .accessibilityLabel(label)
        .accessibilityValue(MatchCopy.selectionValue(isOn))
        .accessibilityAddTraits(.isToggle)
    }
}

/// One field: the tick, Yours → Proposed, the source switch, and — for a field you edited — the flag and
/// whose edit it was. Rows stack Yours over Proposed at the accessibility sizes.
struct MatchFieldRowView: View {
    let row: MatchFieldRow
    let onTick: (Bool) -> Void
    let onChooseSource: (MatchSourceSelection) -> Void

    @Environment(\.dynamicTypeSize) private var dynamicTypeSize
    @State private var expanded = false

    var body: some View {
        HStack(alignment: .top, spacing: Spacing.s) {
            MatchTick(isOn: row.isTicked, label: row.tickLabel, onChange: onTick)
            VStack(alignment: .leading, spacing: Spacing.xs) {
                heading
                if row.isEdited { MatchEditedFlag() }
                values
                if row.isLongText {
                    Button {
                        withAnimation(.easeInOut(duration: 0.2)) { expanded.toggle() }
                    } label: {
                        Text(expanded ? String(localized: "match.show_less") : String(localized: "match.read_all"))
                            .fullTarget()
                    }
                    .font(.subheadline.weight(.semibold))
                    .buttonStyle(.borderless)
                    .accessibilityHint(row.name)
                }
                sourceSwitch
                if let note = row.editedNote {
                    Text(note).font(.footnote).foregroundStyle(.secondary)
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
        }
        .padding(.vertical, Spacing.xxs)
    }

    private var heading: some View {
        // Wraps rather than truncating at large sizes.
        FlowLayout(spacing: Spacing.xs) {
            Text(row.name).font(.headline)
            Text(row.proposedFrom).font(.subheadline).foregroundStyle(.secondary)
        }
        .accessibilityElement(children: .combine)
    }

    private var values: some View {
        VStack(alignment: .leading, spacing: Spacing.xxs) {
            valueLine(String(localized: "match.yours"), row.yours ?? String(localized: "match.empty_value"), isProposed: false)
            valueLine(String(localized: "match.proposed"), row.proposed, isProposed: true)
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(row.valuesLabel)
    }

    @ViewBuilder
    private func valueLine(_ label: String, _ value: String, isProposed: Bool) -> some View {
        let layout = dynamicTypeSize.isAccessibilitySize
            ? AnyLayout(VStackLayout(alignment: .leading, spacing: 2))
            : AnyLayout(HStackLayout(alignment: .firstTextBaseline, spacing: Spacing.s))
        layout {
            Text(label)
                .font(.caption.weight(.semibold))
                .foregroundStyle(.secondary)
                .frame(minWidth: dynamicTypeSize.isAccessibilitySize ? nil : 72, alignment: .leading)
            Text(value)
                .font(.subheadline)
                .foregroundStyle(isProposed ? .primary : .secondary)
                .lineLimit(row.isLongText && !expanded ? 3 : nil)
                .frame(maxWidth: .infinity, alignment: .leading)
        }
    }

    @ViewBuilder
    private var sourceSwitch: some View {
        let selection = Binding(get: { row.selectedSegment }, set: { onChooseSource($0) })
        let label = String(format: String(localized: "match.source_switch_a11y"), row.name)
        switch row.switchStyle {
        case .none:
            EmptyView()
        case .segmented where !dynamicTypeSize.isAccessibilitySize:
            Picker(label, selection: selection) {
                ForEach(row.segments) { Text($0.title).tag($0.selection) }
            }
            .pickerStyle(.segmented)
            .frame(minHeight: TapTarget.minimum)
        case .segmented, .menu:
            // Beyond four segments, or when large text would truncate them: a menu (HIG, Segmented controls).
            Picker(label, selection: selection) {
                ForEach(row.segments) { Text($0.title).tag($0.selection) }
            }
            .pickerStyle(.menu)
            .frame(minHeight: TapTarget.minimum)
        }
    }
}

/// "You edited this", in words on its own fill — never colour alone.
struct MatchEditedFlag: View {
    var body: some View {
        Label(String(localized: "match.you_edited_flag"), systemImage: "pencil")
            .font(.caption.weight(.semibold))
            .foregroundStyle(Color.luEditedInk)
            .padding(.horizontal, Spacing.xs)
            .padding(.vertical, 2)
            .background(RoundedRectangle(cornerRadius: Radius.xs).fill(Color.luEditedFill))
    }
}

/// The cover choice: Keep current (your real cover) and every candidate, one selected — a radio group
/// of buttons, each saying its source and size.
struct MatchCoverPicker: View {
    let cover: MatchCoverSection
    let bookId: String?
    let onChoose: (String) -> Void

    var body: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(alignment: .top, spacing: Spacing.s) {
                ForEach(cover.tiles) { tile in
                    Button {
                        onChoose(tile.id)
                    } label: {
                        tileLabel(tile)
                    }
                    .buttonStyle(.plain)
                    .accessibilityElement(children: .ignore)
                    .accessibilityLabel(tile.accessibilityLabel)
                    .accessibilityAddTraits(tile.isSelected ? [.isButton, .isSelected] : .isButton)
                }
            }
            .padding(.vertical, Spacing.xxs)
        }
        .accessibilityElement(children: .contain)
        .accessibilityLabel(String(localized: "match.section_cover"))
    }

    private func tileLabel(_ tile: MatchCoverTile) -> some View {
        VStack(spacing: Spacing.xxs) {
            Group {
                if tile.isKeepCurrent {
                    BookCoverImage(bookId: bookId, coverPath: cover.bookCoverPath)
                } else {
                    MetadataRemoteCover(url: tile.url)
                }
            }
            .frame(width: 80, height: 80)
            .clipShape(RoundedRectangle(cornerRadius: Radius.m))
            .overlay {
                RoundedRectangle(cornerRadius: Radius.m)
                    .strokeBorder(tile.isSelected ? Color.accentColor : Color.luSeparator, lineWidth: tile.isSelected ? 3 : 0.5)
            }
            .overlay(alignment: .topTrailing) {
                if tile.isSelected {
                    Image(systemName: "checkmark.circle.fill")
                        .symbolRenderingMode(.multicolor)
                        .font(.title3)
                        .padding(Spacing.xxs)
                }
            }
            Text(tile.title).font(.caption.weight(tile.isSelected ? .semibold : .regular))
            if let detail = tile.detail {
                Text(detail).font(.caption2).foregroundStyle(.secondary)
            }
        }
        .frame(minWidth: 88)
        .contentShape(Rectangle())
    }
}

/// Genres or moods: your labels, each removable with its ×, and the suggestions, each a toggle that says
/// where it came from.
struct MatchLabelGroupView: View {
    let group: MatchLabelGroup
    let actions: MatchReviewActions

    var body: some View {
        VStack(alignment: .leading, spacing: Spacing.xs) {
            Text(group.title).font(.headline).accessibilityAddTraits(.isHeader)
            if !group.yours.isEmpty {
                Text(String(localized: "match.yours_kept")).font(.caption.weight(.semibold)).foregroundStyle(.secondary)
                FlowLayout(spacing: Spacing.xs) {
                    ForEach(group.yours) { label in
                        MatchYourLabelChip(label: label) {
                            if label.removed {
                                actions.restoreLabel(group.kind, label.label)
                            } else {
                                actions.removeLabel(group.kind, label.label)
                            }
                        }
                    }
                }
            }
            if !group.suggested.isEmpty {
                Text(String(localized: "match.suggested")).font(.caption.weight(.semibold)).foregroundStyle(.secondary)
                FlowLayout(spacing: Spacing.xs) {
                    ForEach(group.suggested) { suggestion in
                        MatchSuggestionChip(suggestion: suggestion) {
                            actions.toggleSuggestion(group.kind, suggestion.label)
                        }
                    }
                }
            }
        }
        .padding(.vertical, Spacing.xxs)
    }
}

/// One of your labels with its ×: "Remove Science Fiction", or once removed, "Keep Science Fiction".
struct MatchYourLabelChip: View {
    let label: MatchYourLabel
    let onTap: () -> Void

    var body: some View {
        Button(action: onTap) {
            HStack(spacing: Spacing.xxs) {
                Text(label.label)
                    .strikethrough(label.removed)
                    .multilineTextAlignment(.leading)
                Image(systemName: label.removed ? "arrow.uturn.backward" : "xmark")
                    .font(.caption.weight(.bold))
                    .accessibilityHidden(true)
            }
            .font(.subheadline)
            .foregroundStyle(label.removed ? .secondary : .primary)
            .padding(.horizontal, Spacing.s)
            .frame(minHeight: TapTarget.minimum)
            .background(RoundedRectangle(cornerRadius: Radius.l).fill(Color.luFill))
            .contentShape(Rectangle())
        }
        .buttonStyle(PressScaleButtonStyle(scale: .chip))
        .accessibilityLabel(String(
            format: label.removed ? String(localized: "match.restore_label") : String(localized: "match.remove_label"),
            label.label
        ))
    }
}

/// A suggested label: "Thriller · Hardcover", a toggle that is on until you turn it off.
struct MatchSuggestionChip: View {
    let suggestion: MatchSuggestion
    let onTap: () -> Void

    var body: some View {
        Button(action: onTap) {
            HStack(spacing: Spacing.xxs) {
                Image(systemName: suggestion.selected ? "checkmark" : "plus")
                    .font(.caption.weight(.bold))
                    .accessibilityHidden(true)
                Text(suggestion.label).fontWeight(.semibold)
                Text("· \(suggestion.sources)").foregroundStyle(.secondary)
            }
            .font(.subheadline)
            .multilineTextAlignment(.leading)
            .foregroundStyle(suggestion.selected ? Color.luTint : Color.primary)
            .padding(.horizontal, Spacing.s)
            .frame(minHeight: TapTarget.minimum)
            .background(
                RoundedRectangle(cornerRadius: Radius.l)
                    .fill(suggestion.selected ? Color.luTint.opacity(0.13) : Color.luFill)
            )
            .contentShape(Rectangle())
        }
        .buttonStyle(PressScaleButtonStyle(scale: .chip))
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(suggestion.accessibilityLabel)
        .accessibilityValue(MatchCopy.selectionValue(suggestion.selected))
        .accessibilityAddTraits([.isButton, .isToggle])
    }
}

/// Chapter names: one include toggle, the summary, the first rows and Show all; or why a different
/// edition's names can't be matched.
struct MatchChapterSectionView: View {
    let section: MatchChapterSection
    let actions: MatchReviewActions

    @State private var showsAll = false
    private let preview = 3

    var body: some View {
        switch section {
        case .mismatch(let message):
            Label(message, systemImage: "exclamationmark.triangle").font(.subheadline).foregroundStyle(.secondary)
        case .available(let summary, let included, let rows):
            HStack(alignment: .top, spacing: Spacing.s) {
                MatchTick(
                    isOn: included,
                    label: String(localized: "match.apply_chapter_names"),
                    onChange: { actions.setChapterNamesIncluded($0) }
                )
                Text(summary).font(.subheadline).frame(maxWidth: .infinity, alignment: .leading)
            }
            ForEach(showsAll ? rows : Array(rows.prefix(preview))) { row in
                chapterRow(row, enabled: included)
            }
            if rows.count > preview, !showsAll {
                Button {
                    showsAll = true
                } label: {
                    Text(String(format: String(localized: "match.show_all_chapters"), rows.count)).fullTarget()
                }
            }
        }
    }

    private func chapterRow(_ row: MatchChapterRow, enabled: Bool) -> some View {
        HStack(spacing: Spacing.s) {
            MatchTick(isOn: row.selected && enabled, label: row.accessibilityLabel, onChange: { _ in
                actions.toggleChapter(row.ordinal)
            })
            .disabled(!enabled)
            FlowLayout(spacing: Spacing.xxs) {
                Text(row.yours).foregroundStyle(.secondary)
                Image(systemName: "arrow.right").foregroundStyle(.tertiary)
                Text(row.theirs)
            }
            .font(.subheadline)
            .accessibilityHidden(true)
        }
    }
}
