import Charts
import SwiftUI

/// A 7-day bar chart of daily listening time, drawn with native Swift Charts.
///
/// One coral bar per `DayBar`, ordered oldest-to-newest left-to-right so today sits on the right.
/// The y-axis scales to `maxDaySeconds`; when every day is zero the chart renders a flat baseline
/// rather than dividing by zero. Bar entry animates, gated under Reduce Motion. Each bar carries its
/// own accessibility label so VoiceOver reads the day and a human duration.
struct DailyListeningChart: View {
    let days: [DayBar]
    let maxDaySeconds: Int

    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    /// Oldest day first so the chart reads left-to-right with today on the right.
    private var orderedDays: [DayBar] {
        days.sorted { $0.dayOffset > $1.dayOffset }
    }

    /// Upper bound for the y-scale; never zero, so an all-empty week still draws a clean baseline.
    private var yMax: Int {
        max(maxDaySeconds, 1)
    }

    var body: some View {
        Chart(orderedDays) { day in
            BarMark(
                x: .value("Day", label(for: day.dayOffset)),
                y: .value("Seconds", day.seconds)
            )
            .foregroundStyle(Color.listenUpOrange)
            .cornerRadius(6)
            .accessibilityLabel(label(for: day.dayOffset))
            .accessibilityValue(durationLabel(for: day.seconds))
        }
        .chartYScale(domain: 0 ... yMax)
        .chartYAxis(.hidden)
        .chartXAxis {
            AxisMarks { _ in
                AxisValueLabel()
                    .font(.caption2)
                    .foregroundStyle(.secondary)
            }
        }
        .chartPlotStyle { plot in
            plot.frame(height: 132)
        }
        .animation(reduceMotion ? nil : .easeOut(duration: 0.5), value: orderedDays)
    }

    // MARK: - Labels

    /// Short weekday for a day offset (0 = today). "Today" reads clearest for the current day.
    private func label(for dayOffset: Int) -> String {
        if dayOffset == 0 {
            return String(localized: "home.today")
        }
        let date = Calendar.current.date(byAdding: .day, value: -dayOffset, to: Date()) ?? Date()
        return date.formatted(.dateTime.weekday(.narrow))
    }

    /// A human, VoiceOver-friendly duration like "1 hr, 12 min" or "No listening".
    private func durationLabel(for seconds: Int) -> String {
        guard seconds > 0 else { return String(localized: "home.no_listening") }
        return Duration.seconds(seconds).formatted(
            .units(allowed: [.hours, .minutes], width: .wide)
        )
    }
}

// MARK: - Preview

#Preview("Daily Chart") {
    DailyListeningChart(
        days: [
            DayBar(dayOffset: 6, seconds: 1_800),
            DayBar(dayOffset: 5, seconds: 3_600),
            DayBar(dayOffset: 4, seconds: 0),
            DayBar(dayOffset: 3, seconds: 5_400),
            DayBar(dayOffset: 2, seconds: 2_700),
            DayBar(dayOffset: 1, seconds: 900),
            DayBar(dayOffset: 0, seconds: 4_200)
        ],
        maxDaySeconds: 5_400
    )
    .padding()
}
