import CoreGraphics

/// The window's fold, mirroring Android's `Fold` (`client/foldable/Posture.kt`).
///
/// Read from the active division regions (`GeometryProxy.reservedRegions(kind: .division)`, iOS 27.1): Apple's layout
/// API for the fold, rather than hinge-angle callbacks, which are for live interactions and effects. A division is
/// active only while the phone is partly folded, so a flat or closed phone has none.
enum PlayerFold: Equatable {
    case none
    /// Hinge runs across the screen: the upper half stands, the lower half lies flat.
    case tabletop(hinge: CGRect)
    /// Hinge runs down the screen, like an open book.
    case book(hinge: CGRect)

    /// From the active division regions' frames, in the player's own coordinates. The first division decides; a
    /// zero-thickness frame is kept, since a seamless fold is a line.
    static func from(divisionFrames: [CGRect]) -> PlayerFold {
        guard let hinge = divisionFrames.first else { return .none }
        return hinge.width > hinge.height ? .tabletop(hinge: hinge) : .book(hinge: hinge)
    }
}
