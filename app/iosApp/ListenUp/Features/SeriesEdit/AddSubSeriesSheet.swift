import SwiftUI

/// "Add sub-series to Cosmere" — put an existing series inside this one, or create a new one there.
/// Opened from the series page's tile and from the editor; both read the same shared sheet state.
///
/// Every row stays listed: a series already here is greyed out with its reason, and one that lives
/// in another parent says "moves it here" and asks before moving it (`confirmationDialog`, HIG:
/// Action sheets — "confirm a choice the person initiated"). The change goes to the server as soon
/// as it is chosen; there is nothing to save.
struct AddSubSeriesSheet: View {
    let model: AddSubSeriesSheetModel
    let send: (AddSubSeriesAction) -> Void

    var body: some View {
        NavigationStack {
            List {
                Section {
                    Button { send(.newSeriesStarted) } label: {
                        Label(String(localized: "series.new_series"), systemImage: "plus")
                    }
                }
                Section {
                    ForEach(model.candidates) { candidate in
                        Button { send(.chosen(candidate.id)) } label: {
                            SubSeriesCandidateRow(candidate: candidate)
                        }
                        .disabled(!candidate.isSelectable || model.isBusy)
                    }
                    if model.candidates.isEmpty {
                        Text(String(localized: "series.merge_no_matches"))
                            .foregroundStyle(.secondary)
                    }
                }
            }
            .navigationTitle(model.title)
            .navigationBarTitleDisplayMode(.inline)
            .searchable(
                text: Binding(get: { model.query }, set: { send(.queryChanged($0)) }),
                prompt: String(localized: "series.merge_search_placeholder")
            )
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(String(localized: "common.cancel")) { send(.dismissed) }
                }
                if model.isBusy {
                    ToolbarItem(placement: .confirmationAction) { ProgressView() }
                }
            }
            .confirmationDialog(
                model.pendingMove?.title ?? "",
                isPresented: Binding(
                    get: { model.pendingMove != nil },
                    set: { if !$0 { send(.moveCancelled) } }
                ),
                titleVisibility: .visible
            ) {
                Button(String(localized: "series.move_confirm_action")) { send(.moveConfirmed) }
                Button(String(localized: "common.cancel"), role: .cancel) { send(.moveCancelled) }
            }
            .sheet(
                isPresented: Binding(
                    get: { model.newSeries != nil },
                    set: { if !$0 { send(.newSeriesDismissed) } }
                )
            ) {
                if let draft = model.newSeries {
                    NewSeriesForm(
                        title: String(localized: "series.new_series_title"),
                        draft: draft,
                        explanation: SeriesHierarchyText.newSubSeriesBody(name: draft.name, parent: model.parentName),
                        confirmTitle: String(localized: "common.create"),
                        useExistingTitle: String(localized: "series.add_existing_instead"),
                        onNameChange: { send(.newSeriesNameChanged($0)) },
                        onConfirm: { send(.newSeriesConfirmed) },
                        onUseExisting: { id in
                            send(.newSeriesDismissed)
                            send(.chosen(id))
                        },
                        onCancel: { send(.newSeriesDismissed) }
                    )
                }
            }
        }
    }
}

/// One series the sheet offers: cover, name, and where it sits today.
private struct SubSeriesCandidateRow: View {
    let candidate: SubSeriesCandidateItem

    var body: some View {
        HStack(spacing: Spacing.s) {
            BookCoverImage(coverPath: candidate.coverPath)
                .frame(width: 44, height: 44)
                .clipShape(RoundedRectangle(cornerRadius: Radius.s))
                .accessibilityHidden(true)
            VStack(alignment: .leading, spacing: 2) {
                Text(candidate.name)
                    .foregroundStyle(.primary)
                Text(candidate.meta)
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
        }
        .contentShape(Rectangle())
        .accessibilityElement(children: .combine)
    }
}

/// The "New series" / "New parent series" dialog: a name, what creating it will do, and — when the
/// name is taken — the existing series offered instead of a duplicate the server would refuse.
/// A small sheet rather than an alert, because the explanation changes as the name is typed.
struct NewSeriesForm: View {
    let title: String
    let draft: NewSeriesDraftItem
    /// "Creates “White Sand” inside Cosmere." — nil until a name is typed.
    let explanation: String?
    let confirmTitle: String
    let useExistingTitle: String
    let onNameChange: (String) -> Void
    let onConfirm: () -> Void
    let onUseExisting: (String) -> Void
    let onCancel: () -> Void

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    AppTextField(
                        placeholder: String(localized: "series.series_name"),
                        text: Binding(get: { draft.name }, set: { onNameChange($0) }),
                        entry: .words,
                        submitLabel: .done,
                        onSubmit: { if draft.canCreate { onConfirm() } }
                    )
                } footer: {
                    if let existing = draft.existing {
                        Text(SeriesHierarchyText.nameExists(existing.name))
                    } else if let explanation {
                        Text(explanation)
                    }
                }
                if let existing = draft.existing, existing.isSelectable {
                    Section {
                        Button(useExistingTitle) { onUseExisting(existing.id) }
                    }
                }
            }
            .navigationTitle(title)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(String(localized: "common.cancel"), action: onCancel)
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button(confirmTitle, action: onConfirm)
                        .disabled(!draft.canCreate)
                }
            }
        }
        .presentationDetents([.medium, .large])
    }
}
