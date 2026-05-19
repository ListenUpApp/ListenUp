import Foundation
import Shared

// MARK: - Swift 6 strict-concurrency conformances for the SKIE boundary
//
// SKIE bridges Kotlin flows as `AsyncSequence`-conforming Swift classes that it
// does not mark `Sendable`. SKIE flows are documented thread-safe ("no thread
// restriction, two-way cancellation"), and `FlowBridge` collects every one of
// them on the main actor — so the `@unchecked Sendable` assertions below are
// honest, not a cover for a real data race.
//
// This is the single, revisitable home for these conformances: if SKIE later
// ships native `Sendable`/isolation support, delete the matching lines here.
//
// Class-typed offenders only — Kotlin `sealed interface`s bridge as Swift
// *protocols*, which cannot be retroactively `Sendable`-conformed; those are
// handled by targeted, per-site fixes.

extension SkieSwiftFlow: @retroactive @unchecked Sendable {}
extension SkieSwiftStateFlow: @retroactive @unchecked Sendable {}
extension SkieSwiftOptionalFlow: @retroactive @unchecked Sendable {}
