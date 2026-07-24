import SwiftUI

/// The two secondary actions beneath the resume bar on the redesigned Book Detail
/// screen: "Add to Shelf" and "Mark as Finished".
///
/// Two subtle outlined pills — a hairline `separator`-toned border with a
/// coral-colored leading SF Symbol. The finish pill presents a native
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

    var body: some View {
        HStack(spacing: 12) {
            addToShelfPill
            finishPill
        }
    }

    // MARK: - Add to Shelf

    private var addToShelfPill: some View {
        IconLabelButton(
            icon: "bookmark",
            title: String(localized: "book.detail_add_to_shelf"),
            action: onAddToShelf
        )
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
        IconLabelButton(icon: "checkmark", title: String(localized: "book.detail_mark_as_finished")) {
            showFinishConfirmation = true
        }
        .disabled(isMarkingComplete)
        .opacity(isMarkingComplete ? 0.5 : 1)
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
            RoundedRectangle(cornerRadius: 12, style: .continuous)
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
