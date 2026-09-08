import SwiftUI

/// Folders the scanner walked but could not turn into a book.
///
/// A scan issue is the only place a failed import is visible at all: before these existed, a book
/// that failed produced a log line and nothing else — absent from the library with no trace the
/// user could see, which is the exact shape of failure this app is not supposed to have.
///
/// These are not books awaiting a decision, so they share none of the selection/release machinery
/// the held books use. They are statements that something went wrong, each paired with the thing
/// the user would actually do about it. Dismiss is the only action: someone who can see *why* a
/// folder failed fixes it on disk, and rename/move tools inside the app would be a second, worse
/// file manager.
///
/// Its own file for the same reason `ScanIssueSection.kt` is separate from `AdminInboxScreen.kt`.
struct ScanIssueSection: View {
    let issues: [ScanIssueRowModel]
    let onDismiss: (String) -> Void

    var body: some View {
        if !issues.isEmpty {
            VStack(alignment: .leading, spacing: 8) {
                VStack(alignment: .leading, spacing: 2) {
                    Text(String(localized: "admin.inbox_needs_attention"))
                        .font(.title3.weight(.bold))
                    Text(String(localized: "admin.inbox_needs_attention_subtitle"))
                        .font(.subheadline)
                        .foregroundStyle(Color.luLabel2)
                }
                .frame(maxWidth: .infinity, alignment: .leading)

                ForEach(issues) { issue in
                    ScanIssueCard(issue: issue) { onDismiss(issue.id) }
                }
            }
            .padding(.bottom, 16)
        }
    }
}

private struct ScanIssueCard: View {
    let issue: ScanIssueRowModel
    let onDismiss: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack(spacing: 10) {
                Image(systemName: "exclamationmark.triangle")
                    .font(.body.weight(.semibold))
                    .foregroundStyle(.orange)
                Text(issue.headline)
                    .font(.headline)
            }
            // The folder is what the user goes and looks at, so it reads loudest after the
            // headline — and it is library-relative, matching what they see on disk.
            Text(issue.rootRelPath)
                .font(.body)
                .foregroundStyle(.primary)
            Text(issue.fix)
                .font(.subheadline)
                .foregroundStyle(Color.luLabel2)
            // What the scanner literally reported. Last and quiet: useful when the fix above is
            // not enough, noise when it is.
            if let detail = issue.detail, !detail.isEmpty {
                Text(detail)
                    .font(.footnote)
                    .foregroundStyle(Color.luLabel3)
            }
            Button(String(localized: "admin.inbox_issue_dismiss"), action: onDismiss)
                .font(.subheadline.weight(.semibold))
                .buttonStyle(.plain)
                .foregroundStyle(Color.luTint)
                .padding(.top, 2)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(16)
        .background(Color.luSurface2, in: RoundedRectangle(cornerRadius: 14, style: .continuous))
        .overlay(
            RoundedRectangle(cornerRadius: 14, style: .continuous)
                .stroke(Color.luSeparator, lineWidth: 0.5)
        )
    }
}
