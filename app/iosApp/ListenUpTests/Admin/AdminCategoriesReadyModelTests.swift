import Testing
import Shared
@testable import ListenUp

/// The Categories screen's native model carries the two capabilities its controls follow.
struct AdminCategoriesReadyModelTests {
    private func ready(canEdit: Bool, canCurate: Bool) -> AdminCategoriesUiStateReady {
        AdminCategoriesUiStateReady(
            isSaving: false,
            genres: [],
            tree: [],
            expandedIds: [],
            totalBookCount: 0,
            error: nil,
            canEditMetadata: canEdit,
            canCurateLibrary: canCurate
        )
    }

    @Test func aCuratorWhoMayNotEditCarriesCurateOnly() {
        let model = AdminCategoriesReadyModel(from: ready(canEdit: false, canCurate: true))
        #expect(model.canEditMetadata == false)
        #expect(model.canCurateLibrary == true)
    }

    @Test func anEditorWhoMayNotCurateCarriesEditOnly() {
        let model = AdminCategoriesReadyModel(from: ready(canEdit: true, canCurate: false))
        #expect(model.canEditMetadata == true)
        #expect(model.canCurateLibrary == false)
    }
}
