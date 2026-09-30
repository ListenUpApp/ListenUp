import SwiftUI

/// The two secondary actions beneath the resume bar on the redesigned Book Detail
/// screen: "Add to Shelf" and "Mark as Finished".
///
/// Two system `.bordered` buttons — the secondary style beside the resume bar's prominent one
/// (HIG, Buttons: "use a more prominent button style for that option and a less prominent style for
/// the remaining ones"). The finish button presents a native
/// `.confirmationDialog` before committing, disables itself while a mark is in
/// flight, and collapses to a quiet, filled "Finished" state once the book is
/// complete.
///
/// Pure/presentational: it takes display flags and two closures. The assembly screen
/// wires `onAddToShelf` → `observer.openShelfPicker()` and `onMarkFinished` →
/// `observer.markFinished()`.
struct BookActionPills: View {
    let isComplete: Bool
    let isMarkingComplete: Bool
    let onAddToShelf: () -> Void
    let onMarkFinished: () -> Void

    @State private var showFinishConfirmation = false

    private let pillHeight: CGFloat = 44

    /// Side by side while both labels fit on one line; stacked once they would wrap (a narrow
    /// iPhone, a large text size), so the two buttons never end up at different heights with a
    /// two-line label beside a one-line one (HIG, Typography: "Make sure your app's layout adapts to
    /// all font sizes.").
    var body: some View {
        ViewThatFits(in: .horizontal) {
            HStack(spacing: 12) {
                addToShelfPill
                finishPill
            }
            VStack(spacing: 12) {
                addToShelfPill
                finishPill
            }
        }
    }

    // MARK: - Add to Shelf

    private var addToShelfPill: some View {
        Button(action: onAddToShelf) {
            Label(String(localized: "book.detail_add_to_shelf"), systemImage: "bookmark")
                .frame(maxWidth: .infinity)
        }
        .buttonStyle(.bordered)
        .controlSize(.large)
    }

    // MARK: - Mark as Finished

    @ViewBuilder
    private var finishPill: some View {
        if isComplete {
            finishedState
        } else {
            markFinishedButton
        }
    }

    private var markFinishedButton: some View {
        Button {
            showFinishConfirmation = true
        } label: {
            Label(String(localized: "book.detail_mark_as_finished"), systemImage: "checkmark")
                .frame(maxWidth: .infinity)
        }
        .buttonStyle(.bordered)
        .controlSize(.large)
        .disabled(isMarkingComplete)
        .confirmationDialog(
            String(localized: "book.detail_mark_as_finished_prompt"),
            isPresented: $showFinishConfirmation,
            titleVisibility: .visible
        ) {
            Button(String(localized: "book.detail_mark_as_finished"), action: onMarkFinished)
            Button(String(localized: "common.cancel"), role: .cancel) {}
        }
    }

    /// Quiet, disabled confirmation that the book is already finished.
    private var finishedState: some View {
        pillLabel(
            systemImage: "checkmark.circle.fill",
            title: String(localized: "book.detail_finished"),
            iconColor: .listenUpOrange,
            titleColor: .secondary
        )
        .opacity(0.7)
        .accessibilityLabel(Text(String(localized: "book.detail_finished")))
    }

    // MARK: - Shared pill chrome

    private func pillLabel(
        systemImage: String,
        title: String,
        iconColor: Color,
        titleColor: Color
    ) -> some View {
        HStack(spacing: 7) {
            Image(systemName: systemImage)
                .font(.subheadline.weight(.semibold))
                .foregroundStyle(iconColor)
            Text(title)
                .font(.subheadline.weight(.medium))
                .foregroundStyle(titleColor)
        }
        .frame(maxWidth: .infinity)
        .frame(height: pillHeight)
        .overlay {
            RoundedRectangle(cornerRadius: Radius.m, style: .continuous)
                .strokeBorder(Color(.separator), lineWidth: 1.5)
        }
    }
}

// MARK: - Preview

#Preview("Action pills — states") {
    VStack(spacing: 28) {
        // Default.
        BookActionPills(
            isComplete: false,
            isMarkingComplete: false,
            onAddToShelf: {},
            onMarkFinished: {}
        )

        // Marking in progress — finish pill disabled.
        BookActionPills(
            isComplete: false,
            isMarkingComplete: true,
            onAddToShelf: {},
            onMarkFinished: {}
        )

        // Finished — quiet finished state.
        BookActionPills(
            isComplete: true,
            isMarkingComplete: false,
            onAddToShelf: {},
            onMarkFinished: {}
        )
    }
    .padding()
}
