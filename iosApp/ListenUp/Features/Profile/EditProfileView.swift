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

    /// The two-column layout kicks in past this width — wide enough to hold Tagline and
    /// Name side by side with comfortable margins, narrow enough that every iPhone and
    /// narrow Split View stays single-column.
    private static let wideThreshold: CGFloat = 700
    private static let readableMaxWidth: CGFloat = 820

    @State private var width: CGFloat = 0

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
            canSave: observer.isDirty,
            isSaving: observer.isSaving,
            onCancel: { dismiss() },
            onSave: { observer.save() }
        ) {
            sections(observer)
                .frame(maxWidth: Self.readableMaxWidth)
                .frame(maxWidth: .infinity)
                .padding(.horizontal)
                .onGeometryChange(for: CGFloat.self) { $0.size.width } action: { width = $0 }
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

    /// Single column on narrow widths; on a wide width Tagline and Name sit side by side
    /// while Avatar and Password span full width. Driven off the measured content width,
    /// not the horizontal size class (so narrow Split View reads as compact).
    @ViewBuilder
    private func sections(_ observer: EditProfileObserver) -> some View {
        let isWide = width >= Self.wideThreshold

        VStack(spacing: 22) {
            avatarSection(observer)

            if isWide {
                HStack(alignment: .top, spacing: 16) {
                    taglineSection(observer)
                    nameSection(observer)
                }
            } else {
                taglineSection(observer)
                nameSection(observer)
            }

            passwordSection(observer)
        }
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
        }
    }

    @ViewBuilder
    private func avatarPreview(_ observer: EditProfileObserver) -> some View {
        switch observer.stagedAvatar {
        case .image(let data):
            StagedAvatarPreview(data: data, user: observer.user, size: 104)
        case .reverted, .none:
            // Reverted → initials; None → the user's current avatar (initials today).
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
                    label: String(localized: "profile.tagline")
                )
                .fieldCard()

                Text(taglineCount(observer.tagline))
                    .font(.caption2)
                    .foregroundStyle(Color.luLabel3)
                    .padding(.trailing, 6)
            }
        }
    }

    @ViewBuilder
    private func nameSection(_ observer: EditProfileObserver) -> some View {
        ProfileEditSection(
            title: String(localized: "profile.name"),
            subtitle: String(localized: "profile.name_description")
        ) {
            VStack(spacing: 0) {
                AppTextField(
                    placeholder: String(localized: "auth.first_name_placeholder"),
                    text: binding(observer.firstName, observer.setFirstName),
                    label: String(localized: "auth.first_name"),
                    isLast: false,
                    textContentType: .givenName,
                    autocapitalization: .words
                )
                AppTextField(
                    placeholder: String(localized: "auth.last_name_placeholder"),
                    text: binding(observer.lastName, observer.setLastName),
                    label: String(localized: "auth.last_name"),
                    textContentType: .familyName,
                    autocapitalization: .words
                )
            }
            .fieldCard()
        }
    }

    @ViewBuilder
    private func passwordSection(_ observer: EditProfileObserver) -> some View {
        ProfileEditSection(
            title: String(localized: "profile.change_password"),
            subtitle: String(localized: "profile.password_description")
        ) {
            VStack(spacing: 0) {
                AppTextField(
                    placeholder: String(localized: "profile.current_password"),
                    text: binding(observer.currentPassword, observer.setCurrentPassword),
                    label: String(localized: "profile.current_password"),
                    kind: .secure,
                    isLast: false,
                    textContentType: .password
                )
                AppTextField(
                    placeholder: String(localized: "profile.new_password"),
                    text: binding(observer.newPassword, observer.setNewPassword),
                    label: String(localized: "profile.new_password"),
                    kind: .secure,
                    isLast: false,
                    textContentType: .newPassword
                )
                AppTextField(
                    placeholder: String(localized: "auth.confirm_password"),
                    text: binding(observer.confirmPassword, observer.setConfirmPassword),
                    label: String(localized: "auth.confirm_password"),
                    kind: .secure,
                    textContentType: .newPassword
                )
            }
            .fieldCard()
        }
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

/// A titled profile-edit section: a `.headline` header, a secondary `.subheadline`
/// subtitle, then `.fieldCard()`-wrapped content. The iOS realization of the mockup's
/// titled cards — no Android card chrome, just the native edit-sheet look.
private struct ProfileEditSection<Content: View>: View {
    let title: String
    let subtitle: String
    @ViewBuilder var content: () -> Content

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            VStack(alignment: .leading, spacing: 2) {
                Text(title)
                    .font(.headline)
                Text(subtitle)
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
            }
            content()
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

/// Renders a just-picked avatar image, decoded OFF the main thread. Decoding full-resolution
/// PhotosPicker bytes synchronously in `body` (as `avatarPreview` used to via `UIImage(data:)`)
/// hitched the sheet on every render. Shows the user's current avatar until the decode lands.
private struct StagedAvatarPreview: View {
    let data: Data
    let user: User?
    let size: CGFloat

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
            decoded = await Self.decode(data)
        }
    }

    /// `nonisolated async` ⇒ the decode runs on the cooperative pool, never the main actor.
    private nonisolated static func decode(_ data: Data) async -> UIImage? {
        UIImage(data: data)
    }
}
