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

`runDemo` is a convenience wrapper. You can activate the demo seed profile on any server run by setting environment variables directly:

```bash
LISTENUP_SEED_PROFILE=demo \
LISTENUP_LIBRARY_PATH=/path/to/your/library \
LISTENUP_DB_URL=jdbc:sqlite:/path/to/demo.db \
./gradlew :server:run
```
