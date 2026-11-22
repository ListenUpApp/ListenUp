package com.calypsan.listenup.konsist

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/** Planted inputs for [uiStatePropertiesNotFromStateIn], so the rule is known to fire before it is trusted. */
class ViewModelUsesStateInWhileSubscribedRuleSelfTest :
    FunSpec({
        test("a state property derived by stateIn(WhileSubscribed) passes, over several lines") {
            val source =
                """
                |class GoodViewModel : ViewModel() {
                |    /** The screen. */
                |    val state: StateFlow<GoodUiState> =
                |        repository
                |            .observe()
                |            .map { GoodUiState.Ready(it) }
                |            .stateIn(
                |                scope = viewModelScope,
                |                started = WhileSubscribedForUi,
                |                initialValue = GoodUiState.Loading,
                |            )
                |
                |    private val draft: StateFlow<DraftUiState> = MutableStateFlow(DraftUiState.Empty)
                |}
                """.trimMargin()
            uiStatePropertiesNotFromStateIn(source) shouldBe emptyList()
        }

        test("a MutableStateFlow state fails even when another flow in the class uses WhileSubscribed") {
            val source =
                """
                |class BadViewModel : ViewModel() {
                |    val shelves: StateFlow<ShelvesUiState>
                |        field = MutableStateFlow<ShelvesUiState>(ShelvesUiState.Loading)
                |
                |    val viewer: StateFlow<ViewerUiState> = _viewer.asStateFlow()
                |
                |    val eager: StateFlow<EagerUiState> = upstream.stateIn(viewModelScope, SharingStarted.Eagerly, null)
                |
                |    val count: StateFlow<Int> =
                |        counts.stateIn(viewModelScope, WhileSubscribedForUi, 0)
                |
                |    // val commented: StateFlow<OldUiState> = flow.stateIn(viewModelScope, WhileSubscribedForUi, x)
                |}
                """.trimMargin()
            uiStatePropertiesNotFromStateIn(source) shouldBe listOf("shelves", "viewer", "eager")
        }
    })
