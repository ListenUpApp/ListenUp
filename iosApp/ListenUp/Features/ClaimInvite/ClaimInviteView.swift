import SwiftUI
import Shared

/// Public invite redeem flow: enter code → preview → set password → join.
///
/// Navigation is fully automatic on success — `ClaimInviteViewModel` persists a session on
/// claim, flipping `AuthState` to `.authenticated`, and the root routing exits automatically.
/// `onDismiss` is called for the error/back path (sheet dismissal).
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

    // MARK: - Initialization

    init(onDismiss: @escaping () -> Void) {
        self.onDismiss = onDismiss
        self.deepLinkSeed = nil
        _wrapper = State(initialValue: ClaimInviteViewModelWrapper(
            viewModel: Dependencies.shared.claimInviteViewModel
        ))
    }

    init(deepLinkServerURL: String, deepLinkCode: String, deepLinkRemoteURL: String?, onDismiss: @escaping () -> Void) {
        self.onDismiss = onDismiss
        self.deepLinkSeed = (deepLinkServerURL, deepLinkCode, deepLinkRemoteURL)
        _wrapper = State(initialValue: ClaimInviteViewModelWrapper(
            viewModel: Dependencies.shared.claimInviteViewModel
        ))
    }

    // MARK: - Body

    var body: some View {
        Group {
            switch wrapper.phase {
            case .codeEntry:
                codeEntryScreen
            case .lookingUp, .submitting, .claimed:
                loadingScreen
            case .preview:
                previewScreen
            case .error(let message):
                errorScreen(message: message)
            }
        }
        .onAppear {
            if let seed = deepLinkSeed, !didStart {
                didStart = true
                Log.info("ClaimInviteView appeared from deep link — starting lookup")
                wrapper.start(serverURL: seed.serverURL, code: seed.code, remoteURL: seed.remoteURL)
            }
        }
    }

    // MARK: - Screens

    private var codeEntryScreen: some View {
        AuthScaffold {
            AuthLargeHeader(
                title: String(localized: "invite.title"),
                subtitle: String(localized: "invite.subtitle")
            )
            AuthFieldGroup {
                AppTextField(
                    placeholder: String(localized: "invite.code_placeholder"),
                    text: $code,
                    icon: "ticket",
                    autocapitalization: .characters
                )
            }
        } footer: {
            AuthPrimaryButton(
                title: String(localized: "common.continue"),
                isLoading: false
            ) {
                wrapper.lookUp(code: code)
            }
            .disabled(code.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
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
                    .padding(.horizontal, 16)
                    .padding(.vertical, 12)
                }
                AuthLargeHeader(title: String(localized: "invite.set_password_title"))
                AuthFieldGroup {
                    AppTextField(
                        placeholder: String(localized: "auth.first_name"),
                        text: $firstName,
                        icon: "person",
                        isLast: false,
                        textContentType: .givenName,
                        autocapitalization: .words
                    )
                    AppTextField(
                        placeholder: String(localized: "auth.last_name"),
                        text: $lastName,
                        icon: "person",
                        isLast: false,
                        textContentType: .familyName,
                        autocapitalization: .words
                    )
                    AppTextField(
                        placeholder: String(localized: "auth.password_label"),
                        text: $password,
                        kind: .secure,
                        textContentType: .newPassword
                    )
                }
            } footer: {
                AuthPrimaryButton(
                    title: String(localized: "invite.get_started"),
                    isLoading: false
                ) {
                    wrapper.claim(password: password, firstName: firstName, lastName: lastName)
                }
                .disabled(firstName.isEmpty || lastName.isEmpty || password.isEmpty)
            }
        }
    }

    private func errorScreen(message: String) -> some View {
        AuthScaffold {
            ErrorBanner(message: message)
        } footer: {
            AuthPrimaryButton(
                title: String(localized: "common.back"),
                isLoading: false,
                action: onDismiss
            )
        }
    }
}

// MARK: - Previews

#Preview("Claim Invite") {
    ClaimInviteView(onDismiss: {})
}
