import SwiftUI

/// What the name sheet is for: a new genre (optionally under a parent) or a rename.
enum GenreNameSheetTarget: Identifiable {
    case create(parentId: String?, parentName: String?)
    case rename(id: String, currentName: String)

    var id: String {
        switch self {
        case .create(let parentId, _): "create:\(parentId ?? "")"
        case .rename(let id, _): "rename:\(id)"
        }
    }
}

/// One name field, Save disabled until it holds something. Creating under a parent names that
/// parent above the field, because "Add Sub-genre" from a long-press is otherwise easy to lose
/// track of by the time the keyboard is up.
struct GenreNameSheet: View {
    let target: GenreNameSheetTarget
    let onConfirm: (String) -> Void

    @Environment(\.dismiss) private var dismiss
    @State private var name: String

    init(target: GenreNameSheetTarget, onConfirm: @escaping (String) -> Void) {
        self.target = target
        self.onConfirm = onConfirm
        if case .rename(_, let currentName) = target {
            _name = State(initialValue: currentName)
        } else {
            _name = State(initialValue: "")
        }
    }

    private var trimmed: String { name.trimmingCharacters(in: .whitespacesAndNewlines) }

    private var title: String {
        switch target {
        case .create(_, .some): String(localized: "admin.add_subgenre")
        case .create: String(localized: "admin.add_genre")
        case .rename: String(localized: "admin.rename_genre")
        }
    }

    var body: some View {
        NavigationStack {
            VStack(spacing: 16) {
                if case .create(_, let parentName?) = target {
                    Text(parentName)
                        .font(.footnote)
                        .foregroundStyle(Color.luLabel2)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .accessibilityHidden(true)
                }
                AppTextField(
                    placeholder: String(localized: "admin.genre_name"),
                    text: $name,
                    entry: .words,
                    label: String(localized: "admin.genre_name"),
                    icon: "tag",
                    submitLabel: .done,
                    onSubmit: { if !trimmed.isEmpty { confirm() } }
                )
                .fieldCard()
                Spacer()
            }
            .padding()
            .background(Color.luSurface)
            .navigationTitle(title)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(String(localized: "common.cancel")) { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button(String(localized: "common.save")) { confirm() }
                        .disabled(trimmed.isEmpty)
                }
            }
        }
        .presentationDetents([.medium])
    }

    private func confirm() {
        onConfirm(trimmed)
        dismiss()
    }
}

/// Pick a new parent: "Top level" first, then every cycle-safe candidate with its path. Tapping
/// a row commits — moving is cheap and reversible, unlike merging.
struct GenreMoveSheet: View {
    let source: GenrePickModel
    let candidates: [GenrePickModel]
    let onMove: (String?) -> Void

    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            List {
                Button {
                    onMove(nil)
                    dismiss()
                } label: {
                    Text(String(localized: "admin.top_level"))
                        .font(.body)
                }
                if candidates.isEmpty {
                    Text(String(localized: "admin.no_move_target_top_level_only"))
                        .font(.footnote)
                        .foregroundStyle(Color.luLabel2)
                }
                ForEach(candidates) { candidate in
                    Button {
                        onMove(candidate.id)
                        dismiss()
                    } label: {
                        GenrePickRow(pick: candidate)
                    }
                }
            }
            .listStyle(.plain)
            .navigationTitle(String(format: String(localized: "admin.move_to_named"), source.name))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(String(localized: "common.cancel")) { dismiss() }
                }
            }
        }
    }
}

/// Two steps in one sheet: pick a target, then confirm with the book count and a permanence
/// warning. Selecting never commits — merging soft-deletes the source and re-links every one of
/// its books, and nothing in the product can reverse it. The selection is the target's id, not a
/// snapshot, so a candidate renamed or removed under an open sheet drops back to the list.
struct GenreMergeSheet: View {
    let source: GenrePickModel
    let candidates: [GenrePickModel]
    let onConfirm: (String) -> Void

    @Environment(\.dismiss) private var dismiss
    @State private var selectedId: String?

    private var target: GenrePickModel? { candidates.first { $0.id == selectedId } }

    var body: some View {
        NavigationStack {
            Group {
                if let target {
                    confirmStep(target: target)
                } else {
                    candidateStep
                }
            }
            .background(Color.luSurface)
            .navigationTitle(
                target.map { String(format: String(localized: "admin.merge_genre_confirm_title"), $0.name) }
                    ?? String(format: String(localized: "admin.merge_into_named"), source.name)
            )
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    if target != nil {
                        Button(String(localized: "common.back")) { selectedId = nil }
                    } else {
                        Button(String(localized: "common.cancel")) { dismiss() }
                    }
                }
                ToolbarItem(placement: .confirmationAction) {
                    if let target {
                        Button(String(localized: "admin.merge_confirm"), role: .destructive) {
                            onConfirm(target.id)
                            dismiss()
                        }
                    }
                }
            }
        }
    }

    private var candidateStep: some View {
        List {
            if candidates.isEmpty {
                Text(String(localized: "admin.no_merge_target_available"))
                    .font(.footnote)
                    .foregroundStyle(Color.luLabel2)
            }
            ForEach(candidates) { candidate in
                Button {
                    selectedId = candidate.id
                } label: {
                    GenrePickRow(pick: candidate)
                }
            }
        }
        .listStyle(.plain)
    }

    private func confirmStep(target: GenrePickModel) -> some View {
        let format = source.bookCount == 1
            ? String(localized: "admin.merge_genre_confirm_body")
            : String(localized: "admin.merge_genre_confirm_body_plural")
        let body = String(format: format, source.name, target.name, source.bookCount)
        return VStack(alignment: .leading, spacing: 12) {
            Text(body)
                .font(.body)
            Text(String(localized: "common.cannot_be_undone"))
                .font(.body)
                .foregroundStyle(.red)
            Spacer()
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding()
    }
}

/// A candidate row in the move and merge sheets: name over its materialized path.
private struct GenrePickRow: View {
    let pick: GenrePickModel

    var body: some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(pick.name)
                .font(.body)
                .foregroundStyle(.primary)
            Text(pick.path)
                .font(.footnote)
                .foregroundStyle(Color.luLabel2)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .contentShape(Rectangle())
    }
}
