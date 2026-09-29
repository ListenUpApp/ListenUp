import SwiftUI
import Shared

/// Public invite redeem flow: enter code → preview → set password → join.
///
/// On a successful claim `ClaimInviteViewModel` persists a session and flips `AuthState` to
/// `.authenticated`, so the root routing swaps in the authenticated app beneath this view. When
/// presented as a deep-link sheet, though, the sheet is bound to the router outcome — not to auth
/// state — so it does not tear itself down. This view therefore calls `onDismiss()` on `.claimed`
/// (and on the error/back path) to dismiss the panel. Mirrors Android's `JoinScreen`, which
/// dismisses via `LaunchedEffect(Claimed) { onClaimed() }`.
struct ClaimInviteView: View {

    // MARK: - Configuration

    let onDismiss: () -> Void
    private let deepLinkSeed: (serverURL: String, code: String, remoteURL: String?)?

    // MARK: - State

    @State private var wrapper: ClaimInviteViewModelWrapper
    @State private var code = ""
    @State private var firstName = ""
    @State private var lastName = ""
    @State private var password = ""
    @State private var didStart = false
    @FocusState private var focusedField: ClaimInviteFocusField?

    // MARK: - Initialization

    init(onDismiss: @escaping () -> Void) {
        self.onDismiss = onDismiss
        self.deepLinkSeed = nil
        _wrapper = State(initialValue: ClaimInviteViewModelWrapper(
            viewModel: Dependencies.shared.makeClaimInviteViewModel()
        ))
    }

    init(deepLinkServerURL: String, deepLinkCode: String, deepLinkRemoteURL: String?, onDismiss: @escaping () -> Void) {
        self.onDismiss = onDismiss
        self.deepLinkSeed = (deepLinkServerURL, deepLinkCode, deepLinkRemoteURL)
        _wrapper = State(initialValue: ClaimInviteViewModelWrapper(
            viewModel: Dependencies.shared.makeClaimInviteViewModel()
        ))
    }

    // MARK: - Body

    var body: some View {
        // A sheet's own stack, so each phase's `AuthIntro` titles the bar and Cancel has a place
        // (HIG, Sheets: "the Cancel button belongs on the leading edge of the top toolbar").
        NavigationStack {
            phaseScreen
                .toolbar {
                    ToolbarItem(placement: .cancellationAction) {
                        Button(String(localized: "common.cancel"), action: onDismiss)
                    }
                }
        }
        .onAppear {
            if let seed = deepLinkSeed, !didStart {
                didStart = true
                Log.info("ClaimInviteView appeared from deep link — starting lookup")
                wrapper.start(serverURL: seed.serverURL, code: seed.code, remoteURL: seed.remoteURL)
            }
        }
        .onChange(of: wrapper.phase) { _, phase in
            // Claim succeeded: the session is persisted and AuthState has flipped, so the root
            // routing has already swapped in the authenticated app beneath us. Dismiss the panel
            // explicitly — the deep-link sheet is keyed to the router outcome, not auth state, so
            // it won't tear itself down. Without this it sits on the spinner until manually closed.
            if phase == .claimed { onDismiss() }
        }
    }

    @ViewBuilder
    private var phaseScreen: some View {
        switch wrapper.phase {
        case .codeEntry:
            codeEntryScreen
        case .confirmServer(let host, let signedInElsewhere):
            confirmServerScreen(host: host, signedInElsewhere: signedInElsewhere)
        case .lookingUp, .submitting, .claimed:
            loadingScreen
        case .preview:
            previewScreen
        case .error(let message):
            errorScreen(message: message)
        }
    }

    // MARK: - Screens

    private var codeEntryScreen: some View {
        AuthScaffold {
            AuthIntro(
                title: String(localized: "invite.title"),
                subtitle: String(localized: "invite.subtitle")
            )
            AuthFieldGroup {
                AppTextField(
                    placeholder: String(localized: "invite.code_placeholder"),
                    text: $code,
                    entry: .identifier,
                    icon: "ticket",
                    submitLabel: .continue,
                    onSubmit: { if canLookUp { wrapper.lookUp(code: code) } }
                )
            }
        } footer: {
            Button {
                wrapper.lookUp(code: code)
            } label: {
                ActionLabel(title: String(localized: "common.continue"))
            }
            .prominentAction()
            .disabled(!canLookUp)
        }
    }

    /// The link named a server this device is not already pointed at. The address is rendered in
    /// full — in the subtitle and again on its own row — before anything is persisted; that
    /// visibility is the whole point of the step. Declining falls back to manual code entry.
    private func confirmServerScreen(host: String, signedInElsewhere: Bool) -> some View {
        AuthScaffold {
            AuthIntro(
                title: String(localized: "invite.confirm_server_title"),
                subtitle: String(format: String(localized: "invite.confirm_server_body"), host)
            )
            AuthFieldGroup {
                Label(host, systemImage: "server.rack")
                    .font(.headline)
                    .foregroundStyle(.primary)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(.horizontal, Spacing.m)
                    .padding(.vertical, Spacing.s)
            }
            if signedInElsewhere {
                ErrorBanner(message: String(localized: "invite.confirm_server_signed_out_warning"))
            }
        } footer: {
            Button {
                wrapper.confirmServer()
            } label: {
                ActionLabel(title: String(localized: "invite.confirm_server_continue"))
            }
            .prominentAction()
            Button(String(localized: "invite.confirm_server_cancel")) { wrapper.cancelServer() }
                .font(.subheadline.weight(.semibold))
                .foregroundStyle(Color.listenUpOrange)
                .buttonStyle(.plain)
        }
    }

    private var loadingScreen: some View {
        LoadingStateView()
    }

    @ViewBuilder
    private var previewScreen: some View {
        if let preview = wrapper.preview {
            AuthScaffold {
                AuthFieldGroup {
                    VStack(alignment: .leading, spacing: 4) {
                        Text(
                            String(
                                format: String(localized: "invite.preview_title"),
                                preview.invitedByName
                            )
                        )
                        .font(.subheadline.weight(.semibold))
                        .foregroundStyle(.primary)
                        Text("\(preview.serverName) · \(preview.email)")
                            .font(.subheadline)
                            .foregroundStyle(.secondary)
                    }
                    .padding(.horizontal, Spacing.m)
                    .padding(.vertical, Spacing.s)
                }
                AuthIntro(title: String(localized: "invite.set_password_title"))
                AuthFieldGroup {
                    AppTextField(
                        placeholder: String(localized: "auth.first_name"),
                        text: $firstName,
                        entry: .givenName,
                        icon: "person",
                        isLast: false,
                        submitLabel: ClaimInviteFocusField.firstName.submitLabel(last: .join),
                        onSubmit: { advance(from: .firstName) }
                    )
                    .focused($focusedField, equals: .firstName)
                    AppTextField(
                        placeholder: String(localized: "auth.last_name"),
                        text: $lastName,
                        entry: .familyName,
                        icon: "person",
                        isLast: false,
                        submitLabel: ClaimInviteFocusField.lastName.submitLabel(last: .join),
                        onSubmit: { advance(from: .lastName) }
                    )
                    .focused($focusedField, equals: .lastName)
                    AppTextField(
                        placeholder: String(localized: "auth.password_label"),
                        text: $password,
                        entry: .newPassword,
                        kind: .secure,
                        submitLabel: ClaimInviteFocusField.password.submitLabel(last: .join),
                        onSubmit: { advance(from: .password) }
                    )
                    .focused($focusedField, equals: .password)
                }
            } footer: {
                Button {
                    claim()
                } label: {
                    ActionLabel(title: String(localized: "invite.get_started"))
                }
                .prominentAction()
                .disabled(!canClaim)
            }
        }
    }

    private var canLookUp: Bool { !code.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty }

    private var canClaim: Bool { !firstName.isEmpty && !lastName.isEmpty && !password.isEmpty }

    /// Return walks the name fields to the password; Return there joins, as the button would.
    private func advance(from field: ClaimInviteFocusField) {
        FormFocus.advance(from: field, focus: $focusedField) {
            if canClaim { claim() }
        }
    }

    private func claim() {
        wrapper.claim(password: password, firstName: firstName, lastName: lastName)
    }

    private func errorScreen(message: String) -> some View {
        AuthScaffold {
            ErrorBanner(message: message)
        } footer: {
            Button(action: onDismiss) {
                ActionLabel(title: String(localized: "common.back"))
            }
            .prominentAction()
        }
    }
}

// MARK: - Previews

#Preview("Claim Invite") {
    ClaimInviteView(onDismiss: {})
}
