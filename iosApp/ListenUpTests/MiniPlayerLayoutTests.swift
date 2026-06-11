import Testing
@testable import ListenUp

@Suite("MiniPlayerLayout")
struct MiniPlayerLayoutTests {
    @Test("inline placement resolves to the compact form")
    func inlineIsCompact() {
        #expect(MiniPlayerLayout.resolve(isInline: true) == .compact)
    }

    @Test("non-inline placement resolves to the expanded form")
    func expandedIsExpanded() {
        #expect(MiniPlayerLayout.resolve(isInline: false) == .expanded)
    }
}
