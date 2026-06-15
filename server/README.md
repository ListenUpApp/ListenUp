# ListenUp Server

## Demo server

Run a fully seeded demo server in one command:

```bash
./gradlew :server:runDemo
```

This generates a synthetic audiobook library under `server/build/seed-library/`, then starts the server at `http://localhost:8080` with the demo library and a pre-created demo account.

### Prerequisites

`ffmpeg` must be on your `PATH`. The library generator uses it to produce the synthetic audio files.

### Credentials

| Field    | Value                |
|----------|----------------------|
| Email    | `demo@listenup.app`  |
| Password | `demo-password`      |

### Android emulator

Point the app's manual server URL entry at `http://10.0.2.2:8080` — the emulator's loopback alias for the host machine.

### Cleanup

The generated library and demo database live under `server/build/` and are wiped by:

```bash
./gradlew clean
```

### Manual activation

`runDemo` is a convenience wrapper. You can activate the demo seed profile on any server run by
setting `LISTENUP_SEED_PROFILE=demo` directly. When you do, the server looks for the synthetic
library at `build/seed-library` and falls back gracefully (logging a warning) if it is not there.

To get the synthetic library in place first:

```bash
./gradlew :server:generateSeedLibrary
LISTENUP_SEED_PROFILE=demo \
LISTENUP_DB_URL=jdbc:sqlite:/path/to/demo.db \
./gradlew :server:run
```

Or point at your own library by setting `LISTENUP_LIBRARY_PATH` explicitly.
`LISTENUP_LIBRARY_PATH` accepts a single folder or a path-separator-delimited list of
folders (`:` on Unix, `;` on Windows). Each folder is seeded as a root path of the
singleton library on first boot:

```bash
LISTENUP_SEED_PROFILE=demo \
LISTENUP_LIBRARY_PATH=/path/to/audiobooks:/path/to/more/audiobooks \
LISTENUP_DB_URL=jdbc:sqlite:/path/to/demo.db \
./gradlew :server:run
```
