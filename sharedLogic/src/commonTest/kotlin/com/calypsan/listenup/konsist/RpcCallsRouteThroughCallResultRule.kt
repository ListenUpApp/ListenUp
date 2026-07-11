package com.calypsan.listenup.konsist

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * Repository RPC calls route through the factory's bounded, self-healing `callResult`
 * (which delegates to [com.calypsan.listenup.client.data.remote.RpcProxyCache.rpcCall]) — never a
 * hand-rolled `try/catch` that folds a raw `service.method()` result itself.
 *
 * The bounded engine is the whole point of the seam: a transport death heals invisibly (one bounded
 * reconnect + retry), a surviving fault surfaces as a typed `AppResult.Failure`, a `TransportError.
 * Timeout` bounds a hung frame, and a lost-response frame becomes an honest timeout rather than a
 * silent hang. A repository that instead opens the raw proxy (`factory.fooService()`) and wraps it
 * in its own `when (result) { Success/Failure }` fold gets none of that — it is a "never stranded"
 * gap hiding behind a green compile.
 *
 * **The tell.** Every hand-rolled repo aliases the contract result type as
 * `import com.calypsan.listenup.api.result.AppResult as WireAppResult` so it can spell out the
 * `WireAppResult.Success`/`WireAppResult.Failure` fold by hand. Canonical repos
 * ([com.calypsan.listenup.client.data.repository.ShelfRepositoryImpl],
 * [com.calypsan.listenup.client.data.repository.CollectionRepositoryImpl], and now
 * `GenreRepositoryImpl`) carry no such alias — they call `factory.callResult { it.method() }` and
 * let the boundary fold the outcome. So the alias is a precise, uniform proxy for the drift.
 *
 * This rule is a ratchet: it locks the migrated surface and blocks NEW hand-rolled repos. The
 * [RESIDUAL_HANDROLLED_RPC_ALLOWLIST] holds the repos still awaiting migration — the project is
 * rewritten in place, so each entry drops off the list the day its `callResult` migration ships
 * (as `GenreRepositoryImpl` just did). Removing a file from the set is the explicit signal that one
 * of those re-touches has landed; adding one should never be necessary.
 */
class RpcCallsRouteThroughCallResultRule :
    FunSpec({
        test("data/repository/*Impl files fold RPC through callResult, not a hand-rolled WireAppResult alias") {
            val offenders =
                productionScope()
                    .files
                    .filter { it.path.contains("/data/repository/") && it.path.endsWith("Impl.kt") }
                    .filter { f -> RESIDUAL_HANDROLLED_RPC_ALLOWLIST.none { allowed -> f.path.endsWith(allowed) } }
                    .filter { file -> file.imports.any { it.alias?.name == "WireAppResult" } }
                    .map { it.path }

            offenders shouldBe emptyList()
        }
    })

/**
 * Repository impls that still hand-roll the `WireAppResult` fold instead of routing through the
 * factory's `callResult`. Each migrates when its domain is re-touched; removing a file from this set
 * is the explicit signal that its migration has shipped. Adding a new entry should never be
 * necessary — new repos call `factory.callResult { it.method() }` from day one.
 *
 * Legitimately-excluded shapes stay here on purpose, not as debt:
 * - **Long-running RPCs** (`BackupRepositoryImpl`, `ImportRepositoryImpl`) — backup/restore/import
 *   run far past `callResult`'s 15s bound; they need bespoke timeouts, so they own their fold.
 * - **`BookRepositoryImpl` / `MetadataRepositoryImpl`** — mixed read/write surfaces whose fold is
 *   entangled with cover/metadata handling; migrate as a deliberate slice, not a mechanical swap.
 */
private val RESIDUAL_HANDROLLED_RPC_ALLOWLIST: Set<String> =
    setOf(
        "/data/repository/BackupRepositoryImpl.kt",
        "/data/repository/BookEditRepositoryImpl.kt",
        "/data/repository/BookRepositoryImpl.kt",
        "/data/repository/ContributorEditRepositoryImpl.kt",
        "/data/repository/ImportRepositoryImpl.kt",
        "/data/repository/MetadataRepositoryImpl.kt",
        "/data/repository/ProfileEditRepositoryImpl.kt",
        "/data/repository/SeriesEditRepositoryImpl.kt",
    )
