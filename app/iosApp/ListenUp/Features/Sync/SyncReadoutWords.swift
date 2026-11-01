import Foundation
import Shared

/// The sync readout's words ("Synced 2 min ago"): the shared `SyncReadout` rule, worded from the string catalog.
enum SyncReadoutWords {
    static var notSyncedYet: String { String(localized: "shell.sync_not_yet") }

    static func words(_ readout: any SyncReadout) -> String {
        switch readout.sealedType() {
        case .syncing: String(localized: "shell.syncing")
        case .couldNotSync: String(localized: "shell.sync_could_not")
        case .notSyncedYet: notSyncedYet
        case .syncedJustNow: String(localized: "shell.sync_just_now")
        case .syncedMinutesAgo(let ago):
            String(format: String(localized: "shell.sync_minutes_ago"), Int(ago.value.count))
        case .syncedHoursAgo(let ago):
            String(format: String(localized: "shell.sync_hours_ago"), Int(ago.value.count))
        case .syncedDaysAgo(let ago):
            String(format: String(localized: "shell.sync_days_ago"), Int(ago.value.count))
        }
    }
}
