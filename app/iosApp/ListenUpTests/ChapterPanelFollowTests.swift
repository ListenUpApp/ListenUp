import Testing
@testable import ListenUp

/// The iPad chapter panel's follow rule (player spec §3.11): the shared `ChapterListFollow` decides; the panel only
/// tells it which rows are fully in view. Pure, so the decision is tested without a scroll view.
@Suite("ChapterPanelFollow")
struct ChapterPanelFollowTests {
    @Test func theFirstCurrentChapterAlwaysCentresWithoutAnimation() {
        var follow = ChapterPanelFollow()
        #expect(follow.onCurrent(38) == .centre(animated: false))
    }

    @Test func aChangeFollowsWhileTheOldCurrentRowWasFullyVisible() {
        var follow = ChapterPanelFollow()
        _ = follow.onCurrent(10)
        follow.setFullyVisible(10, true)
        #expect(follow.onCurrent(11) == .centre(animated: true))
    }

    @Test func aChangeHoldsWhenTheReaderScrolledAway() {
        var follow = ChapterPanelFollow()
        _ = follow.onCurrent(10)
        follow.setFullyVisible(10, false)
        #expect(follow.onCurrent(11) == .hold)
        #expect(follow.showsBackToCurrent)
    }

    @Test func backToCurrentShowsExactlyWhileTheCurrentRowIsNotFullyVisible() {
        var follow = ChapterPanelFollow()
        _ = follow.onCurrent(5)
        follow.setFullyVisible(5, true)
        #expect(!follow.showsBackToCurrent)
        follow.setFullyVisible(5, false)
        #expect(follow.showsBackToCurrent)
    }

    @Test func nothingCurrentYetOffersNoWayBack() {
        #expect(!ChapterPanelFollow().showsBackToCurrent)
    }

    @Test func theSameChapterAgainIsNotAMove() {
        var follow = ChapterPanelFollow()
        _ = follow.onCurrent(7)
        follow.setFullyVisible(7, false)
        #expect(follow.onCurrent(7) == .hold)
    }

    @Test func reduceMotionNeverAnimates() {
        var follow = ChapterPanelFollow(reduceMotion: true)
        _ = follow.onCurrent(10)
        follow.setFullyVisible(10, true)
        #expect(follow.onCurrent(11) == .centre(animated: false))
    }

    /// "Back to Current Chapter" asks for the same move a follow makes: centred, animated unless Reduce Motion.
    @Test func goingBackCentresTheCurrentRow() {
        #expect(ChapterPanelFollow().backToCurrent() == .centre(animated: true))
        #expect(ChapterPanelFollow(reduceMotion: true).backToCurrent() == .centre(animated: false))
    }
}
