import SwiftUI
@preconcurrency import Shared

/// Presented sheet for editing the current user's profile: avatar, tagline, name, and
/// (optionally) password. Bound to `EditProfileViewModel` via `EditProfileObserver`.
///
/// Text input lives here as `@State`, seeded once from the loaded user; Save dispatches
/// only the fields that actually changed. The sheet dismisses on the first successful
/// save and stays open (surfacing an alert) on failure.
struct EditProfileView: View {
    @Environment(\.dependencies) private var deps
    @Environment(\.dismiss) private var dismiss

    @State private var observer: EditProfileObserver?

    @State private var seeded = false
    @State private var tagline = ""
    @State private var firstName = ""
    @State private var lastName = ""
    @State private var currentPassword = ""
    @State private var newPassword = ""

    /// The values the fields were seeded with, used to detect what changed on Save.
    @State private var originalTagline = ""
    @State private var originalFirstName = ""
    @State private var originalLastName = ""

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
            canSave: hasChanges,
            isSaving: observer.isSaving,
            onCancel: { dismiss() },
            onSave: { save(observer) }
        ) {
            VStack(spacing: 22) {
                ImageEditHeader(
                    shape: .circle,
                    size: 104,
                    isUploading: observer.isSaving,
                    canRemove: false,
                    onPicked: { observer.uploadAvatar($0) },
                    onRemove: {}
                ) {
                    UserAvatarView(user: observer.user, size: 104)
                }
                .padding(.top, 8)

                taglineSection
                nameSection
                passwordSection
            }
        }
        .alert(
            String(localized: "common.error"),
            isPresented: Binding(get: { observer.lastError != nil }, set: { _ in observer.dismissError() })
        ) {
            Button(String(localized: "common.ok"), role: .cancel) { observer.dismissError() }
        } message: {
            Text(observer.lastError ?? "")
        }
        .onChange(of: observer.user) { _, user in seedIfNeeded(from: user) }
        .onChange(of: observer.savedToken) { _, _ in dismiss() }
        .onAppear { seedIfNeeded(from: observer.user) }
    }

    // MARK: - Sections

    private var taglineSection: some View {
        VStack(alignment: .trailing, spacing: 6) {
            AppTextField(
                placeholder: String(localized: "profile.tagline_placeholder"),
                text: $tagline,
                label: String(localized: "profile.tagline")
            )
            .fieldCard()

            Text(taglineCount)
                .font(.caption2)
                .foregroundStyle(Color.luLabel3)
                .padding(.trailing, 6)
        }
        .padding(.horizontal)
    }

    private var nameSection: some View {
        VStack(spacing: 0) {
            AppTextField(
                placeholder: String(localized: "auth.first_name_placeholder"),
                text: $firstName,
                label: String(localized: "auth.first_name"),
                isLast: false
            )
            AppTextField(
                placeholder: String(localized: "auth.last_name_placeholder"),
                text: $lastName,
                label: String(localized: "auth.last_name")
            )
        }
        .fieldCard()
        .padding(.horizontal)
    }

    private var passwordSection: some View {
        VStack(alignment: .leading, spacing: 6) {
            VStack(spacing: 0) {
                AppTextField(
                    placeholder: String(localized: "profile.current_password"),
                    text: $currentPassword,
                    label: String(localized: "profile.current_password"),
                    kind: .secure,
                    isLast: false
                )
                AppTextField(
                    placeholder: String(localized: "profile.new_password"),
                    text: $newPassword,
                    label: String(localized: "profile.new_password"),
                    kind: .secure
                )
            }
            .fieldCard()

            Text(String(localized: "profile.password_description"))
                .font(.caption2)
                .foregroundStyle(Color.luLabel3)
                .padding(.leading, 6)
        }
        .padding(.horizontal)
    }

    // MARK: - Derived

    private var taglineCount: String {
        String(
            format: String(localized: "profile.tagline_char_count"),
            tagline.count,
            Int(EditProfileViewModel.companion.MAX_TAGLINE_LENGTH)
        )
    }

    private var hasChanges: Bool {
        tagline != originalTagline
            || firstName != originalFirstName
            || lastName != originalLastName
            || (!currentPassword.isEmpty && !newPassword.isEmpty)
    }

    // MARK: - Seeding & save

    private func seedIfNeeded(from user: User_?) {
        guard !seeded, let user else { return }
        seeded = true
        let line = user.tagline ?? ""
        tagline = line
        originalTagline = line
        firstName = user.firstName ?? ""
        originalFirstName = firstName
        lastName = user.lastName ?? ""
        originalLastName = lastName
    }

    private func save(_ observer: EditProfileObserver) {
        if tagline != originalTagline {
            observer.saveTagline(tagline)
        }
        if firstName != originalFirstName || lastName != originalLastName {
            observer.saveName(firstName: firstName, lastName: lastName)
        }
        if !currentPassword.isEmpty && !newPassword.isEmpty {
            observer.changePassword(current: currentPassword, new: newPassword)
        }
    }
}
