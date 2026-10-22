import SwiftUI

/// The scanner's flag on this book (spec §3.2.3), for an admin: one sentence — the state carries no per-file count
/// (spec R8) — and Review, which opens Files & Audio. At the top of the hero band on iPhone and of the rail on iPad.
struct ScanWarningBanner: View {
    static var message: String { String(localized: "book.detail_scan_flagged") }
    static var review: String { String(localized: "book.detail_scan_review") }

    let onReview: () -> Void

    var body: some View {
        HStack(spacing: Spacing.s) {
            Image(systemName: "exclamationmark.triangle.fill")
                .accessibilityHidden(true)
            Text(Self.message)
                .frame(maxWidth: .infinity, alignment: .leading)
            Button(Self.review, action: onReview)
                .fontWeight(.semibold)
                .frame(minHeight: 44)
        }
        .font(.subheadline)
        .foregroundStyle(Color.luPlayerError)
        .padding(.horizontal, Spacing.s)
        .padding(.vertical, Spacing.xxs)
        .background(
            Color.luPlayerError.opacity(0.12),
            in: RoundedRectangle(cornerRadius: Radius.m, style: .continuous)
        )
        .accessibilityElement(children: .contain)
    }
}
