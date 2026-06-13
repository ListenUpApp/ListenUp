import SwiftUI
@preconcurrency import Shared

/// Presented sheet for editing a contributor: avatar, name, bio, website, birth/death dates.
struct ContributorEditView: View {
    let contributorId: String

    @Environment(\.dependencies) private var deps
    @Environment(\.dismiss) private var dismiss
    @State private var observer: ContributorEditObserver?

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
                                blurHash: nil,
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
