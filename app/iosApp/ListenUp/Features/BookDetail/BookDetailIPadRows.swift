import CoreGraphics

/// The iPad main column's rows (spec §3.2.4): the stage order, one section a row — except that while in progress on a
/// main column of 720 pt or more, Ratings sits beside Chapters, as the iPad board draws it.
enum BookDetailIPadRows {
    static let pairingMinWidth: CGFloat = 720

    static func rows(
        order: [BookDetailSectionKind],
        stage: BookReadingStage,
        mainWidth: CGFloat
    ) -> [[BookDetailSectionKind]] {
        guard stage == .inProgress, mainWidth >= pairingMinWidth,
              order.contains(.chapters), order.contains(.ratings) else { return order.map { [$0] } }
        var rest = order.filter { $0 != .chapters && $0 != .ratings }.map { [$0] }
        rest.insert([.chapters, .ratings], at: 0)
        return rest
    }
}
