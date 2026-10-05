import SwiftUI

/// The editor's "Place in library": which series this one sits in, with "Move into…", and its own
/// sub-series — reorderable and addable. Every change here goes to the server the moment it is made
/// (it is not part of Save), so the section says so, and offline every control is disabled with an
/// inline banner explaining why (HIG, Offline: be clear about what is unavailable).
///
/// Reordering is the List's own edit mode — `.onMove` with the system handles — plus "Move earlier"
/// and "Move later" as VoiceOver actions on each row; either way a move is announced.
struct SeriesPlacementSection: View {
    let observer: SeriesEditObserver
    @Binding var editMode: EditMode

    private var isEnabled: Bool { observer.isOnline && !observer.hierarchyBusy }
    private var isReordering: Bool { editMode.isEditing }

    var body: some View {
        Section {
            if !observer.isOnline { offlineBanner }
            partOfRow
        } header: {
            Text(String(localized: "series.place_in_library"))
        }

        Section {
            ForEach(observer.childSeries) { child in
                childRow(child)
            }
            .onMove { source, destination in
                guard let first = source.first else { return }
                let movedId = observer.childSeries[first].id
                let order = SubSeriesOrder.moving(
                    observer.childSeries.map(\.id),
                    fromOffsets: source,
                    toOffset: destination
                )
                announce(observer.reorderChildren(to: order, moved: movedId))
            }
            .moveDisabled(!isEnabled)

            Button { observer.sendAddSubSeries(.opened) } label: {
                Label(String(localized: "series.add_subseries"), systemImage: "plus")
            }
            .disabled(!isEnabled)
        } header: {
            HStack {
                Text(subSeriesTitle)
                Spacer()
                if observer.childSeries.count > 1 {
                    Button(String(localized: isReordering ? "common.done" : "common.edit")) {
                        withAnimation { editMode = isReordering ? .inactive : .active }
                    }
                    .font(.subheadline)
                    .textCase(nil)
                    .disabled(!isEnabled && !isReordering)
                }
            }
        } footer: {
            Text(String(localized: "series.hierarchy_caption"))
        }
        .onChange(of: isEnabled) { _, enabled in
            if !enabled { editMode = .inactive }
        }
    }

    private var subSeriesTitle: String {
        let title = String(localized: "series.subseries")
        return observer.childSeries.isEmpty ? title : "\(title) (\(observer.childSeries.count))"
    }

    private var offlineBanner: some View {
        Label {
            VStack(alignment: .leading, spacing: 2) {
                Text(String(localized: "series.offline_title")).font(.subheadline.weight(.semibold))
                Text(String(localized: "series.offline_body")).font(.footnote).foregroundStyle(.secondary)
            }
        } icon: {
            Image(systemName: "wifi.slash").foregroundStyle(Color.luWarning)
        }
        .accessibilityElement(children: .combine)
    }

    private var partOfRow: some View {
        ViewThatFits(in: .horizontal) {
            HStack { partOfLabel; Spacer(); moveIntoButton }
            VStack(alignment: .leading, spacing: Spacing.xs) { partOfLabel; moveIntoButton }
        }
    }

    private var partOfLabel: some View {
        HStack(spacing: Spacing.xs) {
            VStack(alignment: .leading, spacing: 2) {
                Text(String(localized: "series.part_of"))
                    .font(.footnote)
                    .foregroundStyle(.secondary)
                Text(observer.parentName ?? String(localized: "series.top_level"))
            }
            if observer.hierarchyBusy {
                ProgressView().controlSize(.small)
            }
        }
        .accessibilityElement(children: .combine)
    }

    private var moveIntoButton: some View {
        Button(String(localized: "series.move_into")) {
            observer.openParentPicker()
        }
        .buttonStyle(.bordered)
        .disabled(!isEnabled)
        // iPad: the picker is a popover anchored here (I-12); on iPhone it adapts to a sheet.
        .popover(
            isPresented: Binding(
                get: { observer.parentPickerVisible },
                set: { if !$0 { observer.dismissParentPicker() } }
            ),
            arrowEdge: .trailing
        ) {
            ParentPickerView(observer: observer)
                .frame(minWidth: 340, idealWidth: 400, minHeight: 480, idealHeight: 600)
        }
    }

    private func childRow(_ child: EditableSubSeries) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(child.name)
            Text(child.meta).font(.footnote).foregroundStyle(.secondary)
        }
        .accessibilityElement(children: .combine)
        .accessibilityActions {
            if isEnabled {
                Button(String(localized: "shelf.move_earlier")) { step(child.id, by: -1) }
                Button(String(localized: "shelf.move_later")) { step(child.id, by: 1) }
            }
        }
    }

    private func step(_ id: String, by offset: Int) {
        guard let order = SubSeriesOrder.stepping(observer.childSeries.map(\.id), id: id, by: offset) else { return }
        announce(observer.reorderChildren(to: order, moved: id))
    }

    private func announce(_ sentence: String?) {
        if let sentence { VoiceOverAnnouncement.post(sentence) }
    }
}
