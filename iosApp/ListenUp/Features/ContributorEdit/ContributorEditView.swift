import SwiftUI
import Shared

/// Presented sheet for editing a contributor: avatar, name, bio, website, birth/death dates.
struct ContributorEditView: View {
    let contributorId: String

    @Environment(\.dependencies) private var deps
    @Environment(\.dismiss) private var dismiss
    @State private var observer: ContributorEditObserver?
    @State private var showMergeSheet = false

    var body: some View {
        Group {
            if let observer {
                EditSheetScaffold(
                    title: String(localized: "contributor.edit_title"),
                    canSave: observer.hasChanges,
                    isSaving: observer.isSaving,
                    onCancel: { observer.onCancel(); dismiss() },
                    onSave: { observer.onSave() }
                ) {
                    VStack(spacing: 20) {
                        ImageEditHeader(
                            shape: .circle,
                            size: 120,
                            isUploading: observer.isUploadingImage,
                            canRemove: false,
                            onPicked: { observer.onImagePicked($0) },
                            onRemove: {}
                        ) {
                            ContributorAvatar(
                                name: observer.name,
                                imagePath: observer.imagePath,
                                id: contributorId,
                                fontSize: 40
                            )
                        }
                        .padding(.top, 8)

                        Group {
                            AppTextField(
                                placeholder: "",
                                text: Binding(get: { observer.name }, set: { observer.onNameChanged($0) }),
                                label: String(localized: "contributor.edit_name")
                            )
                            .fieldCard()
                            AppTextField(
                                placeholder: String(localized: "contributor.edit_bio_placeholder"),
                                text: Binding(get: { observer.bio }, set: { observer.onBioChanged($0) }),
                                label: String(localized: "contributor.edit_bio"),
                                axis: .vertical
                            )
                            .fieldCard()
                            AppTextField(
                                placeholder: "",
                                text: Binding(get: { observer.website }, set: { observer.onWebsiteChanged($0) }),
                                label: String(localized: "contributor.edit_website")
                            )
                            .fieldCard()
                            EditDateField(
                                label: String(localized: "contributor.edit_born"),
                                isoDate: Binding(get: { observer.birthDate }, set: { observer.onBirthDateChanged($0) })
                            )
                            EditDateField(
                                label: String(localized: "contributor.edit_died"),
                                isoDate: Binding(get: { observer.deathDate }, set: { observer.onDeathDateChanged($0) })
                            )
                            AliasesEditSection(
                                aliases: observer.aliases,
                                onUnmerge: { observer.onUnmergeAlias($0) },
                                onMergeTapped: { showMergeSheet = true }
                            )
                            .fieldCard()
                        }
                        .padding(.horizontal)
                    }
                }
                .alert(
                    String(localized: "common.error"),
                    isPresented: Binding(get: { observer.error != nil }, set: { _ in observer.onDismissError() })
                ) {
                    Button(String(localized: "common.ok"), role: .cancel) { observer.onDismissError() }
                } message: {
                    Text(observer.error ?? "")
                }
                .sheet(isPresented: $showMergeSheet, onDismiss: { observer.onMergeQueryChange("") }) {
                    ContributorMergeSheet(
                        candidates: observer.mergeCandidates,
                        query: observer.mergeQuery,
                        onQueryChange: { observer.onMergeQueryChange($0) },
                        onSelect: { observer.onMergeInto($0) },
                        onDismiss: { showMergeSheet = false }
                    )
                }
                .onChange(of: observer.didFinish) { _, finished in if finished { dismiss() } }
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
private struct AliasesEditSection: View {
    let aliases: [String]
    let onUnmerge: (String) -> Void
    let onMergeTapped: () -> Void

    @State private var pendingUnmerge: String?

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text(String(localized: "contributor.also_known_as"))
                .font(.subheadline.weight(.semibold))
                .foregroundStyle(.primary)

            if aliases.isEmpty {
                Text(String(localized: "contributor.no_aliases_hint"))
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            } else {
                FlowLayout(spacing: 8) {
                    ForEach(aliases, id: \.self) { alias in
                        AliasChip(alias: alias, onRemove: { pendingUnmerge = alias })
                    }
                }
            }

            Button {
                onMergeTapped()
            } label: {
                Label(String(localized: "contributor.merge_button"), systemImage: "arrow.triangle.merge")
            }
            .buttonStyle(.bordered)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
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
    }
}

/// A single removable alias chip.
private struct AliasChip: View {
    let alias: String
    let onRemove: () -> Void

    var body: some View {
        HStack(spacing: 6) {
            Text(alias).font(.callout).foregroundStyle(.primary)
            Button(action: onRemove) {
                Image(systemName: "xmark.circle.fill")
                    .foregroundStyle(Color.luLabel2)
            }
            .buttonStyle(.plain)
            .accessibilityLabel(String(format: String(localized: "contributor.remove_aliasname"), alias))
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 7)
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
