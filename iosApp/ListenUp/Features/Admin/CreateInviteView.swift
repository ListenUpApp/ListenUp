import SwiftUI
import UIKit
import Shared

/// Create Invite — a presented sheet wired to `CreateInviteViewModel` via `CreateInviteObserver`.
///
/// The form gathers WHO'S JOINING (name + email, surfacing validation / email-in-use inline on
/// the right field), an ACCESS LEVEL choice (Member / Admin via ``SelectableOptionCard``), and an
/// INVITE EXPIRES IN segmented control (1 / 7 / 30 days). Create submits; on success the form is
/// replaced by an ``InvitePreviewCard`` carrying the shareable link, with Done and Create-Another
/// actions.
///
/// Note on chrome: the design puts a prominent in-body Create CTA and transitions to a success
/// payoff state, so this uses a bespoke `NavigationStack` + Cancel toolbar rather than
/// `EditSheetScaffold` (whose toolbar Done / dirty-gating doesn't fit a create→success flow).
struct CreateInviteView: View {
    @Environment(\.dismiss) private var dismiss

    let viewModel: CreateInviteViewModel
    @State private var observer: CreateInviteObserver?

    @State private var email = ""
    @State private var role: InviteRole = .member
    @State private var expiresInDays = 7

    var body: some View {
        NavigationStack {
            ScrollView {
                Group {
                    if let observer {
                        if let invite = observer.phase.createdInvite {
                            successContent(observer: observer, invite: invite)
                        } else {
                            formContent(observer: observer)
                        }
                    }
                }
                .padding(.horizontal, 20)
                .padding(.vertical, 16)
                .readableWidth(560)
            }
            .background(Color.luSurface)
            .navigationTitle(String(localized: "admin.create_invite"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    Button(String(localized: "common.cancel")) { dismiss() }
                }
            }
        }
        .onAppear {
            if observer == nil { observer = CreateInviteObserver(viewModel: viewModel) }
        }
    }

    // MARK: - Form

    @ViewBuilder
    private func formContent(observer: CreateInviteObserver) -> some View {
        let validationField = observer.phase.validationField
        VStack(alignment: .leading, spacing: 24) {
            whosJoining(validationField: validationField)
            accessLevel()
            expiry()
            if let banner = observer.phase.bannerMessage {
                ErrorBanner(message: banner)
            }
            PrimaryButton(
                title: String(localized: "admin.create_invite"),
                icon: "link",
                isLoading: observer.phase.isSubmitting
            ) {
                submit(observer: observer)
            }
            .disabled(observer.phase.isSubmitting)
        }
    }

    @ViewBuilder
    private func whosJoining(validationField: InviteField?) -> some View {
        VStack(alignment: .leading, spacing: 0) {
            AdminSectionHeader(String(localized: "admin.whos_joining"))
            VStack(spacing: 0) {
                AppTextField(
                    placeholder: String(localized: "common.email"),
                    text: $email,
                    label: String(localized: "common.email"),
                    icon: "envelope",
                    error: validationField == .email ? String(localized: "admin.valid_email_is_required") : nil,
                    keyboardType: .emailAddress,
                    textContentType: .emailAddress
                )
            }
            .fieldCard()
        }
    }

    @ViewBuilder
    private func accessLevel() -> some View {
        VStack(alignment: .leading, spacing: 0) {
            AdminSectionHeader(String(localized: "admin.access_level"))
            VStack(spacing: 0) {
                SelectableOptionCard(
                    systemImage: "headphones",
                    title: String(localized: "common.member"),
                    subtitle: String(localized: "admin.can_access_the_library"),
                    isSelected: role == .member,
                    onSelect: { role = .member }
                )
                Rectangle().fill(Color.luSeparator).frame(height: 0.5).padding(.leading, 61)
                SelectableOptionCard(
                    systemImage: "shield.fill",
                    title: String(localized: "common.admin"),
                    subtitle: String(localized: "admin.can_manage_users_and_invites"),
                    isSelected: role == .admin,
                    onSelect: { role = .admin }
                )
            }
            .fieldCard()
        }
    }

    @ViewBuilder
    private func expiry() -> some View {
        VStack(alignment: .leading, spacing: 0) {
            AdminSectionHeader(String(localized: "admin.invite_expires_in"))
            Picker("", selection: $expiresInDays) {
                Text(String(localized: "admin.1_day")).tag(1)
                Text(String(format: String(localized: "common.n_days"), "7")).tag(7)
                Text(String(format: String(localized: "common.n_days"), "30")).tag(30)
            }
            .pickerStyle(.segmented)
            .labelsHidden()
        }
    }

    // MARK: - Success

    @ViewBuilder
    private func successContent(observer: CreateInviteObserver, invite: CreatedInviteModel) -> some View {
        VStack(spacing: 16) {
            InvitePreviewCard(
                title: String(format: String(localized: "admin.name_is_invited"), invite.name),
                subtitle: String(format: String(localized: "common.n_days"), String(expiresInDays)),
                url: invite.url,
                onCopy: { UIPasteboard.general.string = invite.url }
            )
            PrimaryButton(title: String(localized: "common.done")) { dismiss() }
            Button {
                resetForm()
                observer.reset()
            } label: {
                Text(String(localized: "admin.create_another"))
                    .font(.headline)
                    .foregroundStyle(Color.luTint)
                    .frame(maxWidth: .infinity)
                    .frame(height: 52)
            }
            .buttonStyle(PressScaleButtonStyle())
        }
        .onAppear {
            // Match the Android affordance: auto-copy the freshly-created link.
            UIPasteboard.general.string = invite.url
        }
    }

    // MARK: - Actions

    private func submit(observer: CreateInviteObserver) {
        observer.createInvite(email: email, role: role, expiresInDays: expiresInDays)
    }

    private func resetForm() {
        email = ""
        role = .member
        expiresInDays = 7
    }
}

#Preview {
    Text("Create Invite is presented as a sheet; live data needs the running app.")
}
