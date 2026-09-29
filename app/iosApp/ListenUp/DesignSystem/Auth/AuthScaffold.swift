import SwiftUI

/// A toolbar action at the leading edge of an auth screen that is not a back step — Sign In's
/// "Servers", which leaves the server rather than popping a page. Pushed screens use the system
/// back button instead.
struct AuthNav {
    var label: String
    var action: () -> Void
}

/// The adaptive shell every full-screen auth screen (Sign In, Create Account, Select Server, …)
/// composes, inside the system's navigation chrome.
///
/// - The title is the navigation bar's large title (`AuthIntro` sets it), and a pushed screen goes
///   back with the system back button (HIG, Toolbars: "the leading edge … a Back button"). The
///   floating glass nav pill and the hand-drawn large title are gone.
/// - Compact (iPhone): scrollable content on the grouped background, with the footer's actions in
///   a `safeAreaBar` at the bottom, where the system draws the scroll-edge effect the tray used to
///   paint by hand.
/// - Regular (iPad / future Mac): the same content and footer in a solid `AuthCard` over the aurora.
///
/// `content` is the form body; `footer` is the primary action plus any secondary links.
struct AuthScaffold<Content: View, Footer: View>: View {
    var deep: Bool = false
    var leadingAction: AuthNav?
    @ViewBuilder var content: Content
    @ViewBuilder var footer: Footer

    @Environment(\.horizontalSizeClass) private var hSize

    private var mode: AuthLayoutMode { AuthLayoutMode(horizontalSizeClass: hSize) }

    var body: some View {
        Group {
            switch mode {
            case .compact:
                compactBody
                    .background(Color(.systemGroupedBackground).ignoresSafeArea())
            case .regular:
                // iPad / future Mac: the centered card floats over the branded aurora.
                AuroraBackdrop(deep: deep, wide: true) { regularBody }
            }
        }
        .navigationBarTitleDisplayMode(.large)
        .toolbar {
            if let leadingAction {
                ToolbarItem(placement: .topBarLeading) {
                    Button(leadingAction.label, action: leadingAction.action)
                }
            }
        }
    }

    // MARK: Compact — full-screen flow

    private var compactBody: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 20) {
                content
            }
            .padding(.horizontal, AuthMetrics.contentHorizontalPadding)
            .padding(.top, 8)
            .padding(.bottom, 12)
        }
        .scrollDismissesKeyboard(.interactively)
        .safeAreaBar(edge: .bottom) {
            VStack(spacing: 12) { footer }
                .padding(.horizontal, AuthMetrics.contentHorizontalPadding)
                .padding(.top, 10)
                .padding(.bottom, 8)
        }
    }

    // MARK: Regular — centered card

    private var regularBody: some View {
        ScrollView {
            AuthCard {
                VStack(alignment: .leading, spacing: 20) {
                    content
                    VStack(spacing: 14) { footer }
                        .frame(maxWidth: .infinity)
                }
            }
            .frame(maxWidth: .infinity)
            .padding(40)
        }
        // iPad's on-screen keyboard covers half the card; dragging the card dismisses it, as on
        // the compact branch.
        .scrollDismissesKeyboard(.interactively)
    }
}

/// The lead-in of an auth screen: it names the screen in the navigation bar's large title and
/// shows an optional accessory (a status mark, a badge) and a subtitle beneath it.
///
/// Sets the title from inside the content so each phase of a multi-step screen (Forgot Password,
/// Pending Approval, Claim Invite) titles itself where it already describes itself.
struct AuthIntro<Accessory: View>: View {
    var title: String
    var subtitle: String?
    @ViewBuilder var accessory: Accessory

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            accessory
            if let subtitle {
                Text(subtitle)
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
                    .fixedSize(horizontal: false, vertical: true)
            }
        }
        .navigationTitle(title)
    }
}

extension AuthIntro where Accessory == EmptyView {
    init(title: String, subtitle: String? = nil) {
        self.init(title: title, subtitle: subtitle) { EmptyView() }
    }
}
