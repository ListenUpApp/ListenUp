# AGENTS.md — ListenUp Server (`server`)

Operational rules for the `:server` module (`CLAUDE.md` is symlinked to this file). These complement the root `AGENTS.md`.

## Targets & Shipping Binary

- **Shared core + thin platform splits:** ~95% of `:server` lives in `commonMain`, targeting JVM (fast local dev and `jvmTest`) and Linux Native (`linuxX64` / `linuxArm64`).
- **Production ships Linux Native (`server.kexe`):** Packaged in `server/Dockerfile.native` (distroless Debian 13, compiled SQLite, minimal static LGPL FFmpeg + GStreamer `fdkaacdec` for xHE-AAC). Keep `server/Dockerfile.native` and Debian release parity intact (`tools/scripts/check-docker-debian-release.sh`).
- **Test where production runs:** Put platform-neutral auth, route, sync, and security specs in `commonTest` (or `linuxX64Test`) whenever possible so native-only bugs do not hide behind `jvmTest`.

## Persistence & Migrations

- **SQLDelight + build-time catalog:** `server/build.gradle.kts` generates `MigrationCatalog.kt` with whole-file SHA-256 checksums and refuses duplicate migration version numbers.
- **Never edit shipped migrations:** Modifying even whitespace or a comment in a shipped `.sql` file breaks checksums on every deployed server. Always add a new numbered migration instead.

## RPC, Auth & Security Boundaries

- **Contract-driven RPCs:** `@Rpc` interfaces live in `:contract` and are wrapped by `tools/rpc-guard-ksp`, which enforces `suspend -> AppResult<*>` and `Flow<RpcEvent<*>>` and strips server internals/stacktraces off the wire (`InternalError` carries `correlationId` only).
- **Auth carrier separation:** `JWT_PROVIDER` (`Authorization` header + single-use query ticket for browser WebSocket upgrades) is strictly separate from `BLOB_READ_PROVIDER` (GET-only `listenup_access` cookie).
- **Single visibility truth & disk safety:** `BookAccessPolicy` governs book/collection visibility across RPCs, streaming routes, and sync. All library filesystem writes must route through `LibraryWriteBroker`.

## Server Konsist Rules

The server architectural suite lives in `server/src/jvmTest/kotlin/com/calypsan/listenup/server/konsist/` and runs in `:server:jvmTest`. Notable enforced rules:

- `LibraryWritesGoThroughBrokerRule`
- `MutatingRpcsAreGatedRule` and `MutatingRoutesAreGatedRule`
- `ReadRpcsNameTheirCallerRule` and `OneAdminCheckRule`
- `PublicAuthMethodsAreBucketedRule`
- `ServerCoroutineScopesInstallExceptionHandlerRule`
- `SidecarParsersAreReadOnly` and `ImagingIsPureRule`
- `NoClientCodeInServerRule` and `PerCallCopiesPassEveryDependencyRule`
