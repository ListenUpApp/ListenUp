import SwiftUI

/// Why a pair of finish days can't be saved. Mirrors `FinishDatesProblem` in sharedLogic, whose
/// `LocalDate`-based rule does not cross the Swift Export boundary usefully.
enum FinishDatesProblem: Equatable {
    case finishedBeforeStarted
    case inTheFuture

    var message: String {
        switch self {
        case .finishedBeforeStarted: String(localized: "book.detail_finish_dates_before_start")
        case .inTheFuture: String(localized: "book.detail_finish_dates_in_future")
        }
    }
}

/// Asks when the reader started and finished a book before marking it finished — Android's dialog
/// and web's, for logging books read before ListenUp.
///
/// Opens on the recorded start day (today if there is none) and today, so tapping Mark as Finished
/// straight away records what the one-tap finish always did. Two compact date pickers in a `Form`
/// (HIG, Pickers → Date pickers: "Use a compact date picker when space is constrained" — it opens
/// the calendar editor in a modal over the sheet). Each picker's range refuses what can't be saved
/// (the start no later than the finish, the finish between the start and today), so an invalid
/// pair is unreachable here; the shared rule still gates the confirm, and the footer says why if a
/// day ever rolls over while the sheet is open.
struct MarkFinishedSheet: View {
    let startedAtMs: Int64?
    let onConfirm: (_ started: Date, _ finished: Date) -> Void

    @Environment(\.dismiss) private var dismiss
    @State private var started: Date
    @State private var finished: Date
    private let now: Int64

    init(startedAtMs: Int64?, onConfirm: @escaping (_ started: Date, _ finished: Date) -> Void) {
        self.startedAtMs = startedAtMs
        self.onConfirm = onConfirm
        let now = BookDetailObserver.nowMs()
        self.now = now
        let opened = BookDetailObserver.finishDaysOpened(startedAtMs: startedAtMs, now: now, calendar: .current)
        _started = State(initialValue: opened.started)
        _finished = State(initialValue: opened.finished)
    }

    private var today: Date { Date(timeIntervalSince1970: Double(now) / 1000) }

    private var problem: FinishDatesProblem? {
        BookDetailObserver.finishDatesProblem(started: started, finished: finished, now: now, calendar: .current)
    }

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    DatePicker(
                        String(localized: "book.detail_started"),
                        selection: $started,
                        in: ...finished,
                        displayedComponents: .date
                    )
                    DatePicker(
                        String(localized: "book.detail_finished"),
                        selection: $finished,
                        in: started...today,
                        displayedComponents: .date
                    )
                } footer: {
                    if let problem {
                        Text(problem.message)
                            .foregroundStyle(.red)
                    }
                }
            }
            .navigationTitle(String(localized: "book.detail_mark_as_finished"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(String(localized: "common.cancel")) { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button(String(localized: "book.detail_mark_as_finished")) {
                        onConfirm(started, finished)
                        dismiss()
                    }
                    .disabled(problem != nil)
                }
            }
        }
        .presentationDetents([.medium])
    }
}

#Preview("MarkFinishedSheet") {
    MarkFinishedSheet(startedAtMs: nil) { _, _ in }
}
