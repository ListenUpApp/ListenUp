// Boots the real Ktor server on a free port, runs a command against it, tears it down.
//
// Isolated by LISTENUP_HOME: the server takes a lockfile on its home directory and refuses to
// start a second instance against the same one, so a throwaway home is both what keeps a test
// run out of a developer's real library and what lets runs overlap.
//
// The throwaway home also boots the server with an EMPTY library, which is what `AuthArcTest`
// wants (NeedsSetup is reachable without a fixture) and what an end-to-end sync check cannot use
// — there is nothing to sync. So the server is pointed at the synthetic library the repo already
// knows how to build: `:server:generateSeedLibrary` (10 ffmpeg-generated books, Gradle-cached
// between runs), handed over as LISTENUP_LIBRARY_PATH, which ApplicationStartup seeds as a folder
// on first boot and scans at startup. Building it is a hard requirement rather than a
// best-effort: a lane that quietly boots with no books would turn the sync proof into a spec that
// passes for the wrong reason.
import { spawn } from 'node:child_process'
import { mkdtemp, rm } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join, resolve, dirname } from 'node:path'
import { fileURLToPath } from 'node:url'
import { createServer, connect } from 'node:net'

const repoRoot = resolve(dirname(fileURLToPath(import.meta.url)), '../../../..')

const freePort = () =>
  new Promise((res) => {
    const s = createServer()
    s.listen(0, () => {
      const { port } = s.address()
      s.close(() => res(port))
    })
  })

// The gap between freePort() releasing its probe socket and the server actually binding is wide
// (a full Gradle invocation), and this harness's own doc comment above says overlapping runs are
// a design goal — so something else can legitimately take the port in between. Checked
// immediately before the server spawn, right after the pre-build below.
const isPortTaken = (port) =>
  new Promise((res) => {
    const socket = connect({ port, host: '127.0.0.1' })
    socket.once('connect', () => {
      socket.destroy()
      res(true)
    })
    socket.once('error', () => res(false))
  })

const waitForHealth = async (url, deadlineMs = 120_000) => {
  const until = Date.now() + deadlineMs
  while (Date.now() < until) {
    try {
      const r = await fetch(`${url}/healthz`)
      if (r.ok) return true
    } catch {
      // not up yet — keep waiting
    }
    await new Promise((r) => setTimeout(r, 500))
  }
  return false
}

// Runs a Gradle invocation to completion and rejects on non-zero exit OR a spawn failure (e.g.
// `./gradlew` not found) — a spawn error left unlistened-for is an uncaught exception in Node,
// which would skip this script's cleanup entirely.
const runGradleToCompletion = (args) =>
  new Promise((res, rej) => {
    const child = spawn('./gradlew', args, { cwd: repoRoot, stdio: 'inherit' })
    child.once('error', rej)
    child.once('exit', (code) =>
      code === 0 ? res() : rej(new Error(`./gradlew ${args.join(' ')} exited ${code}`)),
    )
  })

// Spawns a long-running child and resolves once it's confirmed alive (rejects on a spawn
// failure instead of leaving an unhandled 'error' event — same reasoning as above, but this one
// can't just await 'exit' since the whole point is that it keeps running).
const spawnLongRunning = (cmd, args, opts) =>
  new Promise((res, rej) => {
    const child = spawn(cmd, args, opts)
    child.once('error', rej)
    child.once('spawn', () => res(child))
  })

// `process.kill(pid, 0)` sends no signal — it only probes whether `pid` still exists (throws
// ESRCH once it doesn't). Used instead of re-polling `isPortTaken` for the discovered owner PID
// below: a JVM can close its listening socket — making the port look free — a moment before the
// OS has actually finished reaping the process, so "port free" is a weaker guarantee than
// "this specific PID is gone".
const isPidAlive = (pid) => {
  try {
    process.kill(pid, 0)
    return true
  } catch {
    return false
  }
}

// Finds the PID currently listening on `port` via `ss` (the same idiom this repo's own tooling
// uses to kill-by-port). Returns null if nothing is listening, or if `ss` itself isn't available.
const findPortOwnerPid = (port) =>
  new Promise((res) => {
    const finder = spawn('sh', [
      '-c',
      `ss -lptnH 'sport = :${port}' | grep -oP 'pid=\\K[0-9]+' | head -1`,
    ])
    let out = ''
    finder.stdout?.on('data', (d) => {
      out += d
    })
    finder.once('error', () => res(null))
    finder.once('close', () => {
      const pid = Number(out.trim())
      res(Number.isInteger(pid) && pid > 0 ? pid : null)
    })
  })

// Tears down the server. `child` (the spawned `./gradlew` process) is signalled directly — but
// `:server:runJvm` forks the actual server JVM as a GRANDCHILD (wrapper launcher -> no-daemon
// build JVM -> JavaExec running LauncherKt), and verified empirically that Gradle's no-daemon
// path breaks into its OWN session somewhere in that chain (the wrapper launcher's process group
// and the server JVM's process group were observed to differ), so signalling `child` alone does
// not reliably reach the server JVM.
//
// So the server JVM's owner PID is looked up (via `findPortOwnerPid`) and signalled directly too
// — captured BEFORE any signal goes out, not after: a JVM closes its listening socket as an
// early step of its OWN graceful shutdown, well before the process actually exits, so gating
// this lookup on "is the port still taken" (as an earlier version of this function did) can miss
// the process entirely — observed directly losing that race, with `isPortTaken` reporting free
// while the JVM was still alive for another moment. Waiting on the discovered PID directly (via
// `isPidAlive`/`process.kill(pid, 0)`) is what actually closes the gap.
const killAndWait = async (child, port) => {
  const ownerPid = await findPortOwnerPid(port)

  const signalAll = (signal) => {
    if (child && child.exitCode === null && child.signalCode === null) {
      try {
        child.kill(signal)
      } catch {
        // already gone
      }
    }
    if (ownerPid && isPidAlive(ownerPid)) {
      try {
        process.kill(ownerPid, signal)
      } catch {
        // already gone
      }
    }
  }

  const bothGone = async (deadlineMs) => {
    const until = Date.now() + deadlineMs
    while (Date.now() < until) {
      const childGone = !child || child.exitCode !== null || child.signalCode !== null
      const ownerGone = !ownerPid || !isPidAlive(ownerPid)
      if (childGone && ownerGone) return true
      await new Promise((r) => setTimeout(r, 200))
    }
    return false
  }

  signalAll('SIGTERM')
  if (!(await bothGone(8_000))) {
    signalAll('SIGKILL')
    await bothGone(5_000)
  }

  // Honest over silent: if teardown could not confirm the port is free, say so rather than
  // exiting quietly — a stray listener is exactly the failure this harness must not hide.
  if (await isPortTaken(port)) {
    console.error(`with-server: port ${port} is still occupied after teardown — could not confirm the server process exited`)
  }
}

const port = await freePort()
const url = `http://localhost:${port}`
const home = await mkdtemp(join(tmpdir(), 'listenup-webtest-'))

// Matches `seedLibraryDir` in server/build.gradle.kts — the output the generator task declares.
const seedLibrary = join(repoRoot, 'server', 'build', 'seed-library')

console.log(`server: ${url}  home: ${home}  library: ${seedLibrary}`)

let server = null
let cleanedUp = false

// Idempotent and safe to call from both the normal finally path and a signal handler — a
// pre-build failure or a SIGINT while the server is booting must not leave the temp home behind.
const cleanup = async () => {
  if (cleanedUp) return
  cleanedUp = true
  await killAndWait(server, port)
  await rm(home, { recursive: true, force: true })
}

const onSignal = (signal, exitCode) => () => {
  cleanup().finally(() => process.exit(exitCode))
}
process.on('SIGINT', onSignal('SIGINT', 130))
process.on('SIGTERM', onSignal('SIGTERM', 143))

let code = 1
try {
  // `--no-daemon` matches every other `./gradlew` invocation in .github/workflows/ and avoids
  // routing this build through a shared, persistent Gradle daemon — under the concurrent-runs
  // model this harness is built for (see the file-header comment), two overlapping runs have no
  // business contending over one daemon slot. It does NOT make teardown a simple "kill my direct
  // child, done" though — see the comment on `killAndWait` above for why.
  //
  // This pre-build step is a separate blocking step, deliberately OUTSIDE the health-check deadline below:
  // `:server:runJvm` recompiles on demand, so a cold build would otherwise burn most of that
  // deadline on `kotlinc` instead of on the thing it exists to catch — the server failing to
  // come up.
  await runGradleToCompletion([
    ':server:jvmMainClasses',
    ':server:generateSeedLibrary',
    '--no-daemon',
    '--no-configuration-cache',
    '--no-watch-fs',
  ])

  if (await isPortTaken(port)) {
    throw new Error(
      `port ${port} was taken between allocation and use — refusing to attach to a server ` +
        `this run did not start`,
    )
  }

  // `PORT` is the override HOCON's `application.conf` reads (`ktor.deployment.port = ${?PORT}`)
  // — not a `-D` system property, which the generated `runJvm` JavaExec task has no configured
  // path to forward into the child JVM's environment/config anyway. `LISTENUP_HOME` is read
  // directly by the server (see ApplicationConfig.kt) and is what keeps this run off a
  // developer's real library.
  server = await spawnLongRunning(
    './gradlew',
    [':server:runJvm', '--no-daemon', '--no-configuration-cache', '--no-watch-fs'],
    {
      cwd: repoRoot,
      env: {
        ...process.env,
        LISTENUP_HOME: home,
        PORT: String(port),
        LISTENUP_LIBRARY_PATH: seedLibrary,
      },
      stdio: 'inherit',
    },
  )

  if (!(await waitForHealth(url))) throw new Error('server never became healthy')

  const [cmd, ...args] = process.argv.slice(2)
  const child = await spawnLongRunning(cmd, args, {
    cwd: resolve(dirname(fileURLToPath(import.meta.url)), '..'),
    env: { ...process.env, LU_SERVER_URL: url },
    stdio: 'inherit',
  })
  code = await new Promise((res) => child.once('exit', res))
} catch (err) {
  // Caught explicitly (rather than left to propagate out of the top-level await) so a spawn
  // failure or a pre-build/health-check error prints one clear line instead of a raw Node
  // uncaught-exception stack — the exit code and cleanup below are identical either way, this is
  // purely about not looking like a crash.
  console.error(`with-server: ${err.message}`)
} finally {
  await cleanup()
}

process.exit(code ?? 1)
