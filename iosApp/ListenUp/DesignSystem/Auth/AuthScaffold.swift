import SwiftUI

/// A floating nav control for the top-left of an auth screen.
struct AuthNav {
    var systemImage: String = "chevron.left"
    var label: String
    var action: () -> Void
}

/// The adaptive shell every full-screen auth screen (Sign In, Create Account, Select
/// Server) composes. Compact (iPhone): scrollable content on the solid system grouped
/// background with a floating glass CTA tray pinned to the bottom (scroll-edge fade) and
/// an optional glass nav pill top-left — the aurora reads as mushy at phone size, so it
/// is omitted here. Regular (iPad / future Mac): the same content + footer centered inside
/// a solid `AuthCard` floating over a wide aurora.
///
/// `content` is the form body; `footer` is the primary CTA plus any secondary links —
/// it lives in the floating tray (compact) or at the bottom of the card (regular).
struct AuthScaffold<Content: View, Footer: View>: View {
    var deep: Bool = false
    var nav: AuthNav?
    @ViewBuilder var content: Content
    @ViewBuilder var footer: Footer

    @Environment(\.horizontalSizeClass) private var hSize

    private var mode: AuthLayoutMode { AuthLayoutMode(horizontalSizeClass: hSize) }

    var body: some View {
        switch mode {
        case .compact:
            // iPhone auth sits on the solid system grouped background; glass is still used
            // for the floating controls (nav pill, rescan, CTA tray) over it.
            compactBody
                .background(Color(.systemGroupedBackground).ignoresSafeArea())
        case .regular:
            // iPad / future Mac: the centered card floats over the branded aurora.
            AuroraBackdrop(deep: deep, wide: true) { regularBody }
        }
    }

    // MARK: Compact — full-screen flow

    private var compactBody: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 20) {
                content
            }
            .padding(.horizontal, AuthMetrics.contentHorizontalPadding)
            .padding(.top, nav == nil ? 24 : 64)
            .padding(.bottom, 12)
        }
        .scrollDismissesKeyboard(.interactively)
        .safeAreaInset(edge: .bottom) { compactTray }
        .overlay(alignment: .topLeading) {
            if let nav {
                GlassNavPill(systemImage: nav.systemImage, label: nav.label, action: nav.action)
                    .padding(.leading, AuthMetrics.contentHorizontalPadding)
                    .padding(.top, 8)
            }
        }
    }

    private var compactTray: some View {
        VStack(spacing: 12) { footer }
            .padding(.horizontal, AuthMetrics.contentHorizontalPadding)
            .padding(.top, 10)
            .padding(.bottom, 8)
            .background(
                // Scroll-edge fade: content dissolves into the background beneath the tray.
                LinearGradient(colors: [.clear, Color(.systemGroupedBackground).opacity(0.92)],
                               startPoint: .top, endPoint: .bottom)
                    .padding(.top, -46)
                    .allowsHitTesting(false)
            )
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
        .overlay(alignment: .topLeading) {
            if let nav {
                GlassNavPill(systemImage: nav.systemImage, label: nav.label, action: nav.action)
                    .padding(28)
            }
        }
    }
}

/// Large-title header used inside `AuthScaffold` content (icon/lockup + large-title + sub).
struct AuthLargeHeader<Accessory: View>: View {
    var title: String
    var subtitle: String?
    @ViewBuilder var accessory: Accessory

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            accessory
            Text(title)
                .font(.largeTitle.weight(.bold))
                .foregroundStyle(.primary)
            if let subtitle {
                Text(subtitle)
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
            }
        }
    }
}

extension AuthLargeHeader where Accessory == EmptyView {
    init(title: String, subtitle: String? = nil) {
        self.init(title: title, subtitle: subtitle) { EmptyView() }
    }
}
