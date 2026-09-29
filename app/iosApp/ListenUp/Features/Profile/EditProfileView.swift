import SwiftUI
import Shared

/// Edit-the-current-user's-profile sheet: avatar, tagline, name, and password, committed
/// by a single Save (the scaffold's nav-bar Done).
///
/// The VM owns the entire form buffer, so this view keeps **no** parallel `@State` copy —
/// every field binds get/set straight through the observer, and `isDirty` / `isSaving`
/// come from the VM. The sheet dismisses on the first successful save and stays open
/// (surfacing an alert) on failure.
struct EditProfileView: View {
    @Environment(\.dependencies) private var deps
    @Environment(\.dismiss) private var dismiss

    @State private var observer: EditProfileObserver?
    @FocusState private var focusedField: EditProfileFocusField?

    var body: some View {
        Group {
            if let observer {
                sheet(observer)
            } else {
                LoadingStateView()
            }
        }
        .task {
            let obs = observer ?? EditProfileObserver(viewModel: deps.createEditProfileViewModel())
            observer = obs
        }
    }

    @ViewBuilder
    private func sheet(_ observer: EditProfileObserver) -> some View {
        EditSheetScaffold(
            title: String(localized: "profile.edit_profile_title"),
            hasChanges: observer.isDirty,
            canSave: observer.isDirty,
            isSaving: observer.isSaving,
            onCancel: { dismiss() },
            onSave: { observer.save() }
        ) {
            sections(observer)
        }
        .alert(
            String(localized: "common.error"),
            isPresented: Binding(get: { observer.lastError != nil }, set: { _ in observer.dismissError() })
        ) {
            Button(String(localized: "common.ok"), role: .cancel) { observer.dismissError() }
        } message: {
            Text(observer.lastError ?? "")
        }
        .onChange(of: observer.savedToken) { _, _ in dismiss() }
    }

    // MARK: - Layout

    /// One column of grouped `Form` sections, in the scaffold's readable column at every width.
    @ViewBuilder
    private func sections(_ observer: EditProfileObserver) -> some View {
        avatarSection(observer)
        taglineSection(observer)
        nameSection(observer)
        passwordSection(observer)
    }

    // MARK: - Sections

    @ViewBuilder
    private func avatarSection(_ observer: EditProfileObserver) -> some View {
        ProfileEditSection(
            title: String(localized: "profile.avatar"),
            subtitle: String(localized: "profile.avatar_description")
        ) {
            ImageEditHeader(
                shape: .circle,
                size: 104,
                isUploading: observer.isSaving,
                canRemove: canRemoveAvatar(observer),
                onPicked: { observer.stageAvatarUpload($0) },
                onRemove: { observer.stageAvatarRevert() }
            ) {
                avatarPreview(observer)
            }
            .frame(maxWidth: .infinity)
            .padding(.vertical, Spacing.xs)
        }
    }

    @ViewBuilder
    private func avatarPreview(_ observer: EditProfileObserver) -> some View {
        switch observer.stagedAvatar {
        case .image(let data):
            StagedAvatarPreview(data: data, user: observer.user, size: 104)
        case .reverted:
            // Staged revert: show initials NOW. `UserAvatarView(user:)` observes the live
            // public_profiles row, which still says `avatarType = "image"` until Save persists the
            // revert — so it would keep showing the old photo, making "Remove" look like a no-op.
            InitialsAvatar(
                initials: UserAvatarView.initials(from: observer.user?.displayName ?? ""),
                size: 104,
                isCurrentUser: true
            )
        case .none:
            UserAvatarView(user: observer.user, size: 104)
        }
    }

    @ViewBuilder
    private func taglineSection(_ observer: EditProfileObserver) -> some View {
        ProfileEditSection(
            title: String(localized: "profile.tagline"),
            subtitle: String(localized: "profile.tagline_description")
        ) {
            VStack(alignment: .trailing, spacing: 6) {
                AppTextField(
                    placeholder: String(localized: "profile.tagline_placeholder"),
                    text: binding(observer.tagline, observer.setTagline),
                    entry: .sentences,
                    label: String(localized: "profile.tagline"),
                    submitLabel: EditProfileFocusField.tagline.submitLabel(last: .done),
                    onSubmit: { advance(from: .tagline) }
                )
                .focused($focusedField, equals: .tagline)

                Text(taglineCount(observer.tagline))
                    .font(.caption2)
                    .foregroundStyle(.tertiary)
            }
        }
    }

    @ViewBuilder
    private func nameSection(_ observer: EditProfileObserver) -> some View {
        ProfileEditSection(
            title: String(localized: "profile.name"),
            subtitle: String(localized: "profile.name_description")
        ) {
            Group {
                AppTextField(
                    placeholder: String(localized: "auth.first_name_placeholder"),
                    text: binding(observer.firstName, observer.setFirstName),
                    entry: .givenName,
                    label: String(localized: "auth.first_name"),
                    submitLabel: EditProfileFocusField.firstName.submitLabel(last: .done),
                    onSubmit: { advance(from: .firstName) }
                )
                .focused($focusedField, equals: .firstName)
                AppTextField(
                    placeholder: String(localized: "auth.last_name_placeholder"),
                    text: binding(observer.lastName, observer.setLastName),
                    entry: .familyName,
                    label: String(localized: "auth.last_name"),
                    submitLabel: EditProfileFocusField.lastName.submitLabel(last: .done),
                    onSubmit: { advance(from: .lastName) }
                )
                .focused($focusedField, equals: .lastName)
            }
        }
    }

    @ViewBuilder
    private func passwordSection(_ observer: EditProfileObserver) -> some View {
        ProfileEditSection(
            title: String(localized: "profile.change_password"),
            subtitle: String(localized: "profile.password_description")
        ) {
            Group {
                AppTextField(
                    placeholder: String(localized: "profile.current_password"),
                    text: binding(observer.currentPassword, observer.setCurrentPassword),
                    entry: .password,
                    label: String(localized: "profile.current_password"),
                    kind: .secure,
                    submitLabel: EditProfileFocusField.currentPassword.submitLabel(last: .done),
                    onSubmit: { advance(from: .currentPassword) }
                )
                .focused($focusedField, equals: .currentPassword)
                AppTextField(
                    placeholder: String(localized: "profile.new_password"),
                    text: binding(observer.newPassword, observer.setNewPassword),
                    entry: .newPassword,
                    label: String(localized: "profile.new_password"),
                    kind: .secure,
                    submitLabel: EditProfileFocusField.newPassword.submitLabel(last: .done),
                    onSubmit: { advance(from: .newPassword) }
                )
                .focused($focusedField, equals: .newPassword)
                AppTextField(
                    placeholder: String(localized: "auth.confirm_password"),
                    text: binding(observer.confirmPassword, observer.setConfirmPassword),
                    entry: .newPassword,
                    label: String(localized: "auth.confirm_password"),
                    kind: .secure,
                    submitLabel: EditProfileFocusField.confirmPassword.submitLabel(last: .done),
                    onSubmit: { advance(from: .confirmPassword) }
                )
                .focused($focusedField, equals: .confirmPassword)
            }
        }
    }

    /// Return moves within the name pair and the password trio; the end of a run puts the
    /// keyboard away. Saving stays on the Done button.
    private func advance(from field: EditProfileFocusField) {
        FormFocus.advance(from: field, focus: $focusedField) { focusedField = nil }
    }

    // MARK: - Derived

    private func canRemoveAvatar(_ observer: EditProfileObserver) -> Bool {
        Self.canRemoveAvatar(staged: observer.stagedAvatar, hasImageAvatar: observer.hasImageAvatar)
    }

    /// Remove is offered when there's a real image avatar to clear, or an upload is staged
    /// (so the user can back out of a fresh pick) — never when already reverted. Pure so
    /// the decision is unit-tested without constructing live VM state.
    nonisolated static func canRemoveAvatar(staged: StagedAvatar, hasImageAvatar: Bool) -> Bool {
        switch staged {
        case .image:
            return true
        case .reverted:
            return false
        case .none:
            return hasImageAvatar
        }
    }

    private func taglineCount(_ tagline: String) -> String {
        String(
            format: String(localized: "profile.tagline_char_count"),
            tagline.count,
            Int(EditProfileViewModel.Companion.shared.MAX_TAGLINE_LENGTH)
        )
    }

    /// A `Binding` that reads observer state and writes through a VM setter — the view
    /// owns no field state of its own.
    private func binding(_ value: String, _ set: @escaping (String) -> Void) -> Binding<String> {
        Binding(get: { value }, set: { set($0) })
    }
}

// MARK: - Section helper

/// A titled profile-edit `Form` section: the system header names it, the footer explains it.
private struct ProfileEditSection<Content: View>: View {
    let title: String
    let subtitle: String
    @ViewBuilder var content: () -> Content

    var body: some View {
        Section {
            content()
        } header: {
            Text(title)
        } footer: {
            Text(subtitle)
        }
    }
}

/// Renders a just-picked avatar image, decoded OFF the main thread. Decoding full-resolution
/// PhotosPicker bytes synchronously in `body` (as `avatarPreview` used to via `UIImage(data:)`)
/// hitched the sheet on every render. Shows the user's current avatar until the decode lands.
private struct StagedAvatarPreview: View {
    let data: Data
    let user: User?
    let size: CGFloat

    @Environment(\.displayScale) private var displayScale
    @State private var decoded: UIImage?

    var body: some View {
        Group {
            if let decoded {
                Image(uiImage: decoded)
                    .resizable()
                    .scaledToFill()
            } else {
                UserAvatarView(user: user, size: size)
            }
        }
        // Keyed on byte count (O(1)) rather than the whole multi-MB Data; re-decodes when a new
        // image is picked, and the decode is cancelled if the view goes away.
        .task(id: data.count) {
            decoded = await Self.decode(data, maxPixelSize: Int((size * displayScale).rounded(.up)))
        }
    }

    /// `@concurrent` ⇒ the decode runs on the cooperative pool, never the main actor, even where a
    /// plain `nonisolated async` would run on the caller's. Decodes only the pixels the preview
    /// fills, not the full-resolution photo.
    @concurrent
    private nonisolated static func decode(_ data: Data, maxPixelSize: Int) async -> UIImage? {
        ImageDownsampler.downsampledImage(data: data, maxPixelSize: maxPixelSize)
    }
}
