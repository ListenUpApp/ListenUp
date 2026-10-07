import SwiftUI
import UIKit

/// How long the receipt stays on its own. While VoiceOver runs it stays until dismissed — a timed
/// toast would vanish mid-sentence (HIG, Feedback; lesson M-T1).
enum MatchReceiptTiming {
    static func autoDismissDelay(voiceOverRunning: Bool) -> Duration? {
        voiceOverRunning ? nil : .seconds(8)
    }
}

/// What VoiceOver hears when the receipt arrives or changes: the receipt itself, Undo in flight, or
/// Undo's outcome. Nothing for no receipt.
enum MatchReceiptAnnouncement {
    static func text(for phase: MatchReceiptPhase) -> String? {
        switch phase {
        case .none: nil
        case .shown(let receipt): receipt.undoing ? String(localized: "match.undoing") : receipt.sentence
        case .undone: String(localized: "match.undone")
        case .expired: String(localized: "match.undo_expired")
        }
    }
}

extension View {
    /// Book Detail's receipt after Match details applied: a bottom capsule with Undo and See What Changed.
    func matchReceipt(bookId: String) -> some View {
        modifier(MatchReceiptHost(bookId: bookId))
    }
}

/// Hosts `MatchReceiptViewModel` for one book. When a receipt (or Undo's confirmation) arrives it is
/// announced and VoiceOver focus moves to it, so the person hears what Apply or Undo did.
private struct MatchReceiptHost: ViewModifier {
    let bookId: String

    @Environment(\.dependencies) private var deps
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var observer: MatchReceiptObserver?
    @State private var showsChanges = false
    @AccessibilityFocusState private var isFocused: Bool

    private var phase: MatchReceiptPhase { observer?.phase ?? .none }

    func body(content: Content) -> some View {
        content
            .safeAreaInset(edge: .bottom) {
                if let observer, phase != .none {
                    MatchReceiptCapsule(
                        phase: phase,
                        onUndo: { observer.undo() },
                        onSeeWhatChanged: { showsChanges = true },
                        onDismiss: { observer.dismiss() }
                    )
                    .accessibilityFocused($isFocused)
                    .padding(.horizontal, Spacing.m)
                    .padding(.bottom, Spacing.xs)
                    .transition(reduceMotion ? .opacity : .move(edge: .bottom).combined(with: .opacity))
                }
            }
            .animation(reduceMotion ? .easeInOut(duration: 0.2) : .spring(duration: 0.35), value: phase)
            .sheet(isPresented: $showsChanges) {
                if case .shown(let receipt) = phase {
                    MatchWhatChangedSheet(receipt: receipt, onClose: { showsChanges = false })
                }
            }
            .task(id: bookId) {
                guard observer == nil else { return }
                observer = MatchReceiptObserver(viewModel: deps.createMatchReceiptViewModel(bookId: bookId))
            }
            .onChange(of: phase) { old, new in
                guard let message = MatchReceiptAnnouncement.text(for: new),
                      MatchReceiptAnnouncement.text(for: old) != message else { return }
                VoiceOverAnnouncement.post(message)
                isFocused = true
            }
            .task(id: dismissalKey) {
                guard dismissalKey != nil,
                      let delay = MatchReceiptTiming.autoDismissDelay(voiceOverRunning: UIAccessibility.isVoiceOverRunning)
                else { return }
                try? await Task.sleep(for: delay)
                guard !Task.isCancelled, !showsChanges else { return }
                if case .shown(let receipt) = phase, receipt.undoing { return }
                observer?.dismiss()
            }
    }

    /// Restarts the timer whenever a different receipt or confirmation shows.
    private var dismissalKey: String? {
        switch phase {
        case .none: nil
        case .shown(let receipt): "shown-\(receipt.id)-\(receipt.undoing)"
        case .undone: "undone"
        case .expired: "expired"
        }
    }

}

/// The receipt: what Apply changed, See What Changed, Undo and a close button; or Undo's confirmation.
/// It reflows to a column when large text won't fit one line.
struct MatchReceiptCapsule: View {
    let phase: MatchReceiptPhase
    let onUndo: () -> Void
    let onSeeWhatChanged: () -> Void
    let onDismiss: () -> Void

    @Environment(\.dynamicTypeSize) private var dynamicTypeSize

    var body: some View {
        Group {
            if dynamicTypeSize.isAccessibilitySize {
                // At the accessibility sizes nothing shares a line: the sentence wraps in full and each
                // action gets its own row.
                VStack(alignment: .leading, spacing: Spacing.xxs) {
                    message.fixedSize(horizontal: false, vertical: true)
                    actions
                }
                .frame(maxWidth: .infinity, alignment: .leading)
            } else {
                ViewThatFits(in: .horizontal) {
                    HStack(spacing: Spacing.s) {
                        message.frame(maxWidth: .infinity, alignment: .leading)
                        actions
                    }
                    VStack(alignment: .leading, spacing: Spacing.xs) {
                        message.fixedSize(horizontal: false, vertical: true)
                        HStack(spacing: Spacing.s) { actions }
                    }
                }
            }
        }
        .padding(.horizontal, Spacing.m)
        .padding(.vertical, Spacing.xs)
        .frame(maxWidth: 640)
        .glassControl(in: RoundedRectangle(cornerRadius: Radius.xxl))
        .accessibilityElement(children: .contain)
    }

    @ViewBuilder
    private var message: some View {
        switch phase {
        case .shown(let receipt):
            VStack(alignment: .leading, spacing: 2) {
                Text(receipt.undoing ? String(localized: "match.undoing") : receipt.sentence)
                    .font(.subheadline.weight(.semibold))
                if let error = receipt.undoError {
                    Text(error).font(.footnote).foregroundStyle(.red)
                }
            }
        case .undone:
            Text(String(localized: "match.undone")).font(.subheadline.weight(.semibold))
        case .expired:
            Text(String(localized: "match.undo_expired")).font(.subheadline.weight(.semibold))
        case .none:
            EmptyView()
        }
    }

    @ViewBuilder
    private var actions: some View {
        if case .shown(let receipt) = phase {
            // The 44-point frame sits inside each label: outside it, only the text is tappable.
            Button(action: onSeeWhatChanged) {
                Text(String(localized: "match.see_what_changed_title")).fullTarget()
            }
            .buttonStyle(.borderless)
            if receipt.canUndo {
                if receipt.undoing {
                    ProgressView().frame(minWidth: TapTarget.minimum, minHeight: TapTarget.minimum)
                } else {
                    Button(action: onUndo) {
                        Text(String(localized: "match.undo")).fontWeight(.semibold).fullTarget()
                    }
                    .buttonStyle(.borderless)
                }
            }
        }
        Button(action: onDismiss) {
            Image(systemName: "xmark")
                .font(.footnote.weight(.bold))
                .frame(minWidth: TapTarget.minimum, minHeight: TapTarget.minimum)
                .contentShape(Rectangle())
        }
        .buttonStyle(.borderless)
        .accessibilityLabel(String(localized: "match.dismiss"))
    }
}

extension View {
    /// A text button's label grown to a 44-point target, all of it tappable.
    func fullTarget() -> some View {
        frame(minWidth: TapTarget.minimum, minHeight: TapTarget.minimum).contentShape(Rectangle())
    }
}

/// See What Changed: every change with its source.
struct MatchWhatChangedSheet: View {
    let receipt: MatchReceiptModel
    let onClose: () -> Void

    var body: some View {
        NavigationStack {
            List {
                Section {
                    ForEach(Array(receipt.changes.enumerated()), id: \.offset) { _, line in
                        Text(line)
                    }
                } footer: {
                    Text(receipt.sentence)
                }
            }
            .listStyle(.insetGrouped)
            .navigationTitle(String(localized: "match.what_changed_title"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button(String(localized: "common.done"), action: onClose)
                }
            }
        }
        .presentationDetents([.medium, .large])
        .presentationDragIndicator(.visible)
    }
}
