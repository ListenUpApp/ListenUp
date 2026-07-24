package com.calypsan.listenup.konsist

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * One-shot events use `Channel<Event>(Channel.BUFFERED).receiveAsFlow()` — never
 * `StateFlow<Event?>`. A nullable-event StateFlow replays its last value to every new
 * subscriber, so a navigation/snackbar event re-fires on re-subscription (rotation,
 * back-navigation). Compliance references: `HomeViewModel.snackbarMessages`,
 * `SeeAllSearchViewModel.navActions`, `AdminBackupViewModel.downloadSaved`.
 *
 * Implementation note: like [ViewModelUsesStateInWhileSubscribedRule], this is a
 * text-level check — Konsist can't AST-match generic property types across lines.
 * It flags any ViewModel in `client/presentation/` whose body declares a
 * `StateFlow<…Event?>`-shaped property.
 *
 * By-design exclusions (NOT migration debt — do not add one-shot event flows here):
 *  - `RestoreBackupViewModel`: `progress: StateFlow<BackupEvent?>` is a latest-value
 *    projection of the server's restore-progress stream (`stateIn(WhileSubscribed)`);
 *    conflation to the newest event is the desired semantics for a progress meter,
 *    and replay-on-resubscribe is correct there.
 */
class OneShotEventsUseChannelRule :
    FunSpec({
        val byDesignExclusions = setOf("RestoreBackupViewModel")
        val nullableEventStateFlow = Regex("""StateFlow<\w*Event\?>""")

        test("no ViewModel exposes one-shot events as StateFlow<Event?>") {
            val offenders =
                productionScope()
                    .classes()
                    .filter { it.path.contains("/sharedLogic/") }
                    .filter { it.path.contains("/client/presentation/") }
                    .filter { it.name.endsWith("ViewModel") }
                    .filterNot { it.name in byDesignExclusions }
                    .filter { vm -> nullableEventStateFlow.containsMatchIn(vm.text) }
                    .map { it.name }
            offenders shouldBe emptyList()
        }
    })
