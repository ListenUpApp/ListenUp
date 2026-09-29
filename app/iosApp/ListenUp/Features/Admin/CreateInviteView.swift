import SwiftUI
import UIKit
import Shared

/// Create Invite — a presented sheet wired to `CreateInviteViewModel` via `CreateInviteObserver`.
///
/// A grouped `Form` gathers WHO'S JOINING (name + email, surfacing validation / email-in-use inline on
/// the right field), an ACCESS LEVEL choice (Member / Admin via ``SelectableOptionRow``), and an
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
            Group {
                if let observer {
                    if let invite = observer.phase.createdInvite {
                        ScrollView {
                            successContent(observer: observer, invite: invite)
                                .padding(.horizontal, 20)
                                .padding(.vertical, 16)
                                .readableWidth(560)
                        }
                    } else {
                        formContent(observer: observer)
                    }
                }
            }
            .background(Color.luSurface)
            .navigationTitle(String(localized: "admin.create_invite"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(String(localized: "common.cancel")) { dismiss() }
                }
            }
        }
        .onAppear {
            if observer == nil { observer = CreateInviteObserver(viewModel: viewModel) }
        }
    }

    // MARK: - Form

    /// A system grouped `Form`: each question is a section with a real header, the access level is
    /// a checkmarked single-choice section, and the fields get the list's insets and keyboard
    /// avoidance. HIG, Lists and tables.
    @ViewBuilder
    private func formContent(observer: CreateInviteObserver) -> some View {
        let validationField = observer.phase.validationField
        Form {
            Section(String(localized: "admin.whos_joining")) {
                AppTextField(
                    placeholder: String(localized: "common.email"),
                    text: $email,
                    entry: .email,
                    label: String(localized: "common.email"),
                    icon: "envelope",
                    error: validationField == .email ? String(localized: "admin.valid_email_is_required") : nil
                )
            }
            Section(String(localized: "admin.access_level")) {
                SelectableOptionRow(
                    systemImage: "headphones",
                    title: String(localized: "common.member"),
                    subtitle: String(localized: "admin.can_access_the_library"),
                    isSelected: role == .member,
                    onSelect: { role = .member }
                )
                SelectableOptionRow(
                    systemImage: "shield.fill",
                    title: String(localized: "common.admin"),
                    subtitle: String(localized: "admin.can_manage_users_and_invites"),
                    isSelected: role == .admin,
                    onSelect: { role = .admin }
                )
            }
            Section(String(localized: "admin.invite_expires_in")) {
                Picker(String(localized: "admin.invite_expires_in"), selection: $expiresInDays) {
                    Text(String(localized: "admin.1_day")).tag(1)
                    Text(String(format: String(localized: "common.n_days"), "7")).tag(7)
                    Text(String(format: String(localized: "common.n_days"), "30")).tag(30)
                }
                .pickerStyle(.segmented)
                .labelsHidden()
            }
            Section {
                if let banner = observer.phase.bannerMessage {
                    ErrorBanner(message: banner)
                        .listRowInsets(EdgeInsets())
                        .listRowBackground(Color.clear)
                }
                Button {
                    submit(observer: observer)
                } label: {
                    ActionLabel(
                        title: String(localized: "admin.create_invite"),
                        systemImage: "link",
                        isBusy: observer.phase.isSubmitting
                    )
                }
                .prominentAction()
                .disabled(observer.phase.isSubmitting)
                .listRowInsets(EdgeInsets())
                .listRowBackground(Color.clear)
            }
        }
        .readableListWidth(560)
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
            Button { dismiss() } label: {
                ActionLabel(title: String(localized: "common.done"))
            }
            .prominentAction()
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
