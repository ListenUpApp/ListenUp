import SwiftUI

/// Book Detail's states (spec §3.2.3): the admin's scan warning, the offline reason in the hero and the error.
/// Split out of `BookDetailView.swift`, which sits at SwiftLint's type-body cap.
@MainActor
extension BookDetailView {
    /// The scanner's flag, for an admin only (`showScanWarning`); Review opens Files & Audio.
    @ViewBuilder
    func scanWarning(_ observer: BookDetailObserver) -> some View {
        if observer.reading.showScanWarning {
            ScanWarningBanner { detailsSheet = .filesAndAudio }
        }
    }

    /// The book cannot be shown: what happened, Retry (ask the server again — a reconnect runs catch-up, and the page
    /// turns Ready by itself when the book arrives) and the way back to the Library. HIG, Navigation: the back button
    /// is there too; a visible Back is the board's.
    func errorView(message: String) -> some View {
        ContentUnavailableView {
            Label(String(localized: "book.detail_error_title"), systemImage: "exclamationmark.triangle")
        } description: {
            Text(message)
        } actions: {
            Button(action: { observer?.retryConnection() }) {
                ActionLabel(title: String(localized: "common.retry"), systemImage: "arrow.clockwise")
            }
            .prominentAction()
            .frame(maxWidth: 240)
            Button(String(localized: "book.detail_back_to_library")) { dismiss() }
                .buttonStyle(.bordered)
                .controlSize(.large)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }
}

/// Why Book Detail's primary is disabled (spec §2.9, §3.2.3 Offline): the server is unreachable and the book is not
/// downloaded. It sits between the Resume unit and the disabled primary, with Retry.
struct OfflineReasonView: View {
    static var message: String { String(localized: "book.detail_offline_reason") }

    let onRetry: () -> Void

    var body: some View {
        HStack(spacing: Spacing.s) {
            Image(systemName: "icloud.slash")
                .foregroundStyle(.secondary)
                .accessibilityHidden(true)
            Text(Self.message)
                .font(.subheadline)
                .frame(maxWidth: .infinity, alignment: .leading)
            Button(String(localized: "common.retry"), action: onRetry)
                .buttonStyle(.borderedProminent)
                .controlSize(.regular)
        }
        .padding(Spacing.s)
        .background(Color(.secondarySystemBackground), in: RoundedRectangle(cornerRadius: Radius.l, style: .continuous))
        .accessibilityElement(children: .contain)
    }
}

/// The sheets the Details rows open (spec D32, D36).
enum BookDetailsSheet: Identifiable {
    case filesAndAudio, documents

    var id: Self { self }
}
