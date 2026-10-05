import SwiftUI

/// "Move “Mistborn” into…" — the whole library as a tree, with the two pinned choices above it.
///
/// Rows the cycle check rules out stay in the tree, greyed (`.disabled`) with their reason drawn
/// beside them and read as the VoiceOver value: the series itself ("This series"), everything inside
/// it ("Inside Mistborn"), and its current parent ("Current"). A row with sub-series has a chevron
/// that expands it in place, disabled rows included, so the reader can always see what is where.
/// Searching flattens the tree and names each match's place: "in Cosmere · 8 books".
///
/// Choosing a row moves the series at once and the picker closes; the editor's "Part of" row shows
/// a busy indicator until sync brings the new parent back.
struct ParentPickerView: View {
    let observer: SeriesEditObserver

    @ScaledMetric(relativeTo: .body) private var indentStep: CGFloat = 20

    private var isSearching: Bool { !observer.parentQuery.trimmingCharacters(in: .whitespaces).isEmpty }

    var body: some View {
        NavigationStack {
            List {
                Section {
                    topLevelRow
                    Button { observer.startNewParent() } label: {
                        Label(String(localized: "series.new_parent"), systemImage: "plus")
                    }
                }
                Section {
                    ForEach(observer.parentRows) { row in
                        pickerRow(row)
                    }
                    if observer.parentRows.isEmpty, isSearching {
                        Text(String(localized: "series.merge_no_matches"))
                            .foregroundStyle(.secondary)
                    }
                }
            }
            .navigationTitle(SeriesHierarchyText.moveIntoTitle(series: observer.name))
            .navigationBarTitleDisplayMode(.inline)
            .searchable(
                text: Binding(get: { observer.parentQuery }, set: { observer.onParentQueryChange($0) }),
                prompt: String(localized: "series.merge_search_placeholder")
            )
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(String(localized: "common.cancel")) { observer.dismissParentPicker() }
                }
            }
        }
    }

    /// "Top level (no parent)" — disabled with a "Current" chip when the series is already there.
    private var topLevelRow: some View {
        Button { observer.moveToTopLevel() } label: {
            HStack {
                Text(String(localized: "series.top_level_no_parent"))
                    .foregroundStyle(observer.isTopLevel ? .secondary : .primary)
                Spacer()
                if observer.isTopLevel { ReasonChip(text: String(localized: "series.picker_current")) }
            }
            .contentShape(Rectangle())
        }
        .disabled(observer.isTopLevel)
        .accessibilityValue(observer.isTopLevel ? Text(String(localized: "series.picker_current")) : Text(""))
    }

    private func pickerRow(_ row: ParentPickerItem) -> some View {
        let reason = row.reason?.text(seriesName: observer.name)
        return HStack(spacing: Spacing.xs) {
            if !isSearching {
                Color.clear.frame(width: CGFloat(row.depth) * indentStep, height: 1)
                    .accessibilityHidden(true)
                chevron(row)
            }
            Button { observer.chooseParent(row.id) } label: {
                HStack(spacing: Spacing.xs) {
                    VStack(alignment: .leading, spacing: 2) {
                        Text(row.name)
                            .foregroundStyle(row.isSelectable ? .primary : .secondary)
                        if let meta = row.meta {
                            Text(meta).font(.footnote).foregroundStyle(.secondary)
                        }
                    }
                    Spacer(minLength: Spacing.xs)
                    if let reason { ReasonChip(text: reason) }
                }
                .contentShape(Rectangle())
            }
            .buttonStyle(.borderless)
            .disabled(!row.isSelectable)
            .accessibilityElement(children: .ignore)
            .accessibilityLabel(row.meta.map { "\(row.name), \($0)" } ?? row.name)
            .accessibilityValue(reason.map { Text($0) } ?? Text(""))
            .accessibilityAddTraits(.isButton)
        }
    }

    /// Expands or collapses a row's sub-series; a leaf keeps the space so names line up.
    @ViewBuilder
    private func chevron(_ row: ParentPickerItem) -> some View {
        if row.hasChildren {
            Button { observer.toggleParentNode(row.id) } label: {
                Image(systemName: "chevron.right")
                    .font(.footnote.weight(.semibold))
                    .rotationEffect(.degrees(row.isExpanded ? 90 : 0))
                    .foregroundStyle(.secondary)
                    .frame(width: 20)
                    .minimumTapTarget(visualSize: 20)
            }
            .buttonStyle(.borderless)
            .accessibilityLabel(
                Text("\(String(localized: row.isExpanded ? "common.collapse" : "common.expand")), \(row.name)")
            )
        } else {
            Color.clear.frame(width: 20, height: 1).accessibilityHidden(true)
        }
    }
}

/// A small capsule naming why a row can't be chosen — "Current", "This series", "Inside Mistborn".
private struct ReasonChip: View {
    let text: String

    var body: some View {
        Text(text)
            .font(.caption.weight(.semibold))
            .foregroundStyle(.secondary)
            .padding(.horizontal, Spacing.xs)
            .padding(.vertical, 2)
            .background(Color.luFill, in: Capsule())
            .accessibilityHidden(true)
    }
}
