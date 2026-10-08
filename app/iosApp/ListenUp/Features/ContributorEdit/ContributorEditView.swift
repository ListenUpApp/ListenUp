import SwiftUI
import Shared

/// Presented sheet for editing a contributor: avatar, name, bio, website, birth/death dates.
struct ContributorEditView: View {
    let contributorId: String
    /// Called with the surviving contributor's id when a merge commits. After a rename-collision
    /// merge the contributor this sheet was editing has been soft-deleted, so the presenter must go
    /// somewhere other than back to it; after an alias merge the survivor is this contributor,
    /// reloaded.
    var onMergedInto: ((String) -> Void)?

    @Environment(\.dependencies) private var deps
    @Environment(\.dismiss) private var dismiss
    @State private var observer: ContributorEditObserver?
    @State private var showMergeSheet = false

    var body: some View {
        Group {
            if let observer {
                EditSheetScaffold(
                    title: String(localized: "contributor.edit_title"),
                    hasChanges: observer.hasChanges,
                    canSave: observer.hasChanges,
                    isSaving: observer.isSaving,
                    onCancel: { observer.onCancel(); dismiss() },
                    onSave: { observer.onSave() }
                ) {
                    Section {
                        ImageEditHeader(
                            shape: .circle,
                            size: 120,
                            isUploading: observer.isUploadingImage,
                            canRemove: false,
                            onPicked: { observer.onImagePicked($0) },
                            onRemove: {}
                        ) {
                            // Non-streaming (streamsContributorPhoto == false): the picked local
                            // staging file in displayImagePath renders immediately before Save,
                            // mirroring how BookEditView shows displayCoverPath.
                            ContributorAvatar(
                                name: observer.name,
                                imagePath: observer.displayImagePath,
                                id: contributorId,
                                fontSize: 40
                            )
                        }
                        .frame(maxWidth: .infinity)
                        .listRowBackground(Color.clear)
                    }

                    Section {
                        AppTextField(
                            placeholder: "",
                            text: Binding(get: { observer.name }, set: { observer.onNameChanged($0) }),
                            entry: .words,
                            label: String(localized: "contributor.edit_name")
                        )
                        AppTextField(
                            placeholder: String(localized: "contributor.edit_bio_placeholder"),
                            text: Binding(get: { observer.bio }, set: { observer.onBioChanged($0) }),
                            entry: .sentences,
                            label: String(localized: "contributor.edit_bio"),
                            axis: .vertical
                        )
                        AppTextField(
                            placeholder: "",
                            text: Binding(get: { observer.website }, set: { observer.onWebsiteChanged($0) }),
                            entry: .url,
                            label: String(localized: "contributor.edit_website")
                        )
                    }

                    Section {
                        EditDateField(
                            label: String(localized: "contributor.edit_born"),
                            isoDate: Binding(get: { observer.birthDate }, set: { observer.onBirthDateChanged($0) })
                        )
                        EditDateField(
                            label: String(localized: "contributor.edit_died"),
                            isoDate: Binding(get: { observer.deathDate }, set: { observer.onDeathDateChanged($0) })
                        )
                    }

                    AliasesEditSection(
                        aliases: observer.aliases,
                        canCurate: observer.canCurateLibrary,
                        onUnmerge: { observer.onUnmergeAlias($0) },
                        onMergeTapped: {
                            // The VM computes merge candidates only while it believes
                            // the picker is open — tell it before presenting the sheet.
                            observer.onMergeDialogOpened()
                            showMergeSheet = true
                        }
                    )
                }
                .alert(
                    String(localized: "common.error"),
                    isPresented: Binding(get: { observer.error != nil }, set: { _ in observer.onDismissError() })
                ) {
                    Button(String(localized: "common.ok"), role: .cancel) { observer.onDismissError() }
                } message: {
                    Text(observer.error ?? "")
                }
                .alert(
                    String(localized: "contributor.rename_collision_title"),
                    isPresented: Binding(
                        get: { observer.renameCollisionCandidate != nil },
                        set: { if !$0 { observer.onDismissRenameCollision() } }
                    ),
                    presenting: observer.renameCollisionCandidate
                ) { _ in
                    // Merging is Curate library's: without it the collision offers only keep-separate.
                    if observer.canCurateLibrary {
                        Button(String(localized: "contributor.rename_collision_merge")) {
                            observer.onConfirmMergeOnRename()
                        }
                    }
                    Button(String(localized: "contributor.rename_collision_keep_separate")) {
                        observer.onKeepSeparateOnRename()
                    }
                    Button(String(localized: "common.cancel"), role: .cancel) {
                        observer.onDismissRenameCollision()
                    }
                } message: { candidate in
                    Text(String(
                        format: String(localized: "contributor.rename_collision_body"),
                        observer.name,
                        candidate.name
                    ))
                }
                .sheet(isPresented: $showMergeSheet, onDismiss: { observer.onMergeDialogDismissed() }) {
                    ContributorMergeSheet(
                        candidates: observer.mergeCandidates,
                        query: observer.mergeQuery,
                        onQueryChange: { observer.onMergeQueryChange($0) },
                        onSelect: { observer.onMergeInto($0) },
                        onDismiss: { showMergeSheet = false }
                    )
                }
                .onChange(of: observer.didFinish) { _, finished in
                    guard finished else { return }
                    // Report the merge BEFORE dismissing: once this sheet is gone the observer goes
                    // with it, and the presenter would have no way to learn where the survivor is.
                    if let merged = observer.mergedIntoContributorId { onMergedInto?(merged) }
                    dismiss()
                }
            } else {
                ProgressView().frame(maxWidth: .infinity, maxHeight: .infinity)
            }
        }
        .task(id: contributorId) {
            let obs = ContributorEditObserver(viewModel: deps.createContributorEditViewModel())
            observer = obs
            obs.loadContributor(contributorId: contributorId)
        }
    }
}

/// "Also Known As" block in the contributor editor: removable alias chips + a merge entry point.
/// Merging and unmerging are Curate library's: without `canCurate` the chips are read-only and the
/// merge button is gone.
private struct AliasesEditSection: View {
    let aliases: [String]
    let canCurate: Bool
    let onUnmerge: (String) -> Void
    let onMergeTapped: () -> Void

    @State private var pendingUnmerge: String?

    var body: some View {
        Section(String(localized: "contributor.also_known_as")) {
            Group {
                if aliases.isEmpty {
                    Text(String(localized: "contributor.no_aliases_hint"))
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                } else {
                    FlowLayout(spacing: 8) {
                        ForEach(aliases, id: \.self) { alias in
                            AliasChip(alias: alias, onRemove: canCurate ? { pendingUnmerge = alias } : nil)
                        }
                    }
                    .padding(.vertical, Spacing.xxs)
                }
            }
            .confirmationDialog(
                pendingUnmerge.map { String(format: String(localized: "contributor.unmerge_aliasname"), $0) } ?? "",
                isPresented: Binding(get: { pendingUnmerge != nil }, set: { if !$0 { pendingUnmerge = nil } }),
                titleVisibility: .visible
            ) {
                Button(String(localized: "contributor.unmerge_confirm"), role: .destructive) {
                    if let alias = pendingUnmerge { onUnmerge(alias) }
                    pendingUnmerge = nil
                }
                Button(String(localized: "common.cancel"), role: .cancel) { pendingUnmerge = nil }
            } message: {
                if let alias = pendingUnmerge {
                    Text(String(format: String(localized: "contributor.unmerge_body"), alias))
                }
            }

            if canCurate {
                Button {
                    onMergeTapped()
                } label: {
                    Label(String(localized: "contributor.merge_button"), systemImage: "arrow.triangle.merge")
                }
            }
        }
    }
}

/// A single alias chip, removable when `onRemove` is given.
private struct AliasChip: View {
    let alias: String
    let onRemove: (() -> Void)?

    var body: some View {
        HStack(spacing: 6) {
            Text(alias).font(.callout).foregroundStyle(.primary)
            if let onRemove {
                Button(action: onRemove) {
                    Image(systemName: "xmark.circle.fill")
                        .foregroundStyle(Color.secondary)
                }
                .buttonStyle(.plain)
                .accessibilityLabel(String(format: String(localized: "contributor.remove_aliasname"), alias))
            }
        }
        .padding(.horizontal, Spacing.s)
        .padding(.vertical, Spacing.xs)
        .background(Color.luFill, in: Capsule())
    }
}

/// Searchable picker for choosing another author to merge the current contributor into.
private struct ContributorMergeSheet: View {
    let candidates: [MergeCandidate]
    let query: String
    let onQueryChange: (String) -> Void
    let onSelect: (String) -> Void
    let onDismiss: () -> Void

    @State private var pendingTarget: MergeCandidate?

    var body: some View {
        NavigationStack {
            List(candidates) { candidate in
                Button { pendingTarget = candidate } label: {
                    Text(candidate.name).foregroundStyle(.primary)
                }
            }
            .navigationTitle(String(localized: "contributor.merge_title"))
            .navigationBarTitleDisplayMode(.inline)
            .searchable(
                text: Binding(get: { query }, set: { onQueryChange($0) }),
                prompt: String(localized: "contributor.merge_search_placeholder")
            )
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(String(localized: "common.cancel")) { onDismiss() }
                }
            }
            .confirmationDialog(
                pendingTarget?.name ?? "",
                isPresented: Binding(get: { pendingTarget != nil }, set: { if !$0 { pendingTarget = nil } }),
                titleVisibility: .visible
            ) {
                Button(String(localized: "contributor.merge_confirm"), role: .destructive) {
                    if let target = pendingTarget { onSelect(target.id) }
                    pendingTarget = nil
                    onDismiss()
                }
                Button(String(localized: "common.cancel"), role: .cancel) { pendingTarget = nil }
            } message: {
                Text(String(localized: "contributor.merge_body"))
            }
        }
    }
}
