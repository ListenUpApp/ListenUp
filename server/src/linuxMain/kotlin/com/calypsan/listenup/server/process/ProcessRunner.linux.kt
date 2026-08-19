@file:OptIn(ExperimentalForeignApi::class)

package com.calypsan.listenup.server.process

import com.calypsan.listenup.server.logging.loggerFor
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.CPointerVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.MemScope
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.convert
import kotlinx.cinterop.cstr
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.set
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import kotlinx.coroutines.CompletableDeferred
import platform.posix.EINTR
import platform.posix.O_RDWR
import platform.posix.SIGKILL
import platform.posix.STDERR_FILENO
import platform.posix.STDIN_FILENO
import platform.posix.STDOUT_FILENO
import platform.posix.X_OK
import platform.posix._SC_OPEN_MAX
import platform.posix._exit
import platform.posix.access
import platform.posix.close
import platform.posix.dup2
import platform.posix.errno
import platform.posix.fork
import platform.posix.getenv
import platform.posix.kill as posixKill
import platform.posix.open
import platform.posix.pipe
import platform.posix.read
import platform.posix.sysconf
import platform.posix.waitpid
import rawexec.execv
import rawexec.lu_close_fds_from

private val log = loggerFor<ProcessRunner>()

/** Shell-style "128 + signal" exit code reported when `waitpid` says the child died from a signal. */
private const val SIGNALED_EXIT_BASE = 128

/** Low 7 bits of a `waitpid` status: zero means the child exited normally (glibc's `WIFEXITED`). */
private const val WAIT_STATUS_SIGNAL_MASK = 0x7f

/** The exit code lives in the next 8 bits up (glibc's `WEXITSTATUS`). */
private const val WAIT_STATUS_EXIT_SHIFT = 8
private const val WAIT_STATUS_EXIT_MASK = 0xff

private const val READ_BUFFER_BYTES = 4096

/** The first descriptor the child may not keep: 0, 1 and 2 are redirected, everything above is ours. */
private const val FIRST_INHERITABLE_FD = 3u

/**
 * Ceiling for the child's close() walk when `close_range` is unavailable — the pre-5.9-kernel path
 * only; the syscall itself always sweeps to `UINT_MAX`.
 *
 * Container defaults do set `RLIMIT_NOFILE` above this (1,048,576 is common), so the clamp is real —
 * but descriptors are handed out lowest-free-first, so a number above 65,536 means the server is
 * genuinely holding ~65,536 open files, which it never is. A bounded walk beats a million wasted
 * syscalls in a process that is about to `execv` anyway.
 */
private const val MAX_FD_SCAN = 65_536L

/**
 * `posix_spawn` is unavailable in Kotlin/Native's `platform.posix` bindings on Linux — verified
 * empirically against the K/N 2.4.0/2.4.10 klibs, which generate only the `_POSIX_SPAWN`
 * feature-test constant, not the function family (`<spawn.h>` isn't part of the header set
 * `posix.def` is generated from). `fork()` + `execv()` is the fallback every mainstream language
 * runtime uses for the same reason.
 *
 * `$PATH` resolution happens in the **parent**, before `fork()` — never `execvp`/`execlp` in the
 * child. Those `$PATH`-searching variants call `getenv` and build candidate pathnames, which can
 * allocate; POSIX 1003.1-2017 Table 2-4 excludes them from the async-signal-safe function list for
 * exactly that reason. A thread other than the forking one can be holding the C allocator's lock at
 * the instant of `fork()` — `fileIoDispatcher` is `Dispatchers.Default`, a genuinely multithreaded
 * pool, so that thread really exists — and the child would hang forever on a lock it can never see
 * released. `execv()` (plain, no `$PATH` search) IS async-signal-safe.
 *
 * `execv` is called via the `rawexec` cinterop (`src/nativeInterop/cinterop/rawexec.def`), not
 * `platform.posix.execv` — the stock binding maps its `const char *path` to a convenience Kotlin
 * `String?` parameter, and marshalling a Kotlin `String` into a C string at the call site opens an
 * implicit, malloc-backed interop memory scope, silently reintroducing the exact allocation-in-child
 * hazard `execv` was chosen to avoid. The `rawexec` declaration keeps `path` a raw `CPointer<ByteVar>`,
 * built in the parent alongside `argv` — pre-fork, where allocating is unremarkable — and handed
 * straight through. Every other thing the child touches is a pointer or a descriptor number built
 * before the fork for the same reason, `/dev/null` included: the child runs `dup2`, a descriptor
 * sweep, `execv` and `_exit`, and never returns into Kotlin/coroutine machinery, since that would
 * run the caller's whole stack twice.
 */
actual class ProcessRunner {
    private val started = CompletableDeferred<Unit>()
    private val lock = SynchronizedObject()

    /**
     * The running children's pids, empty when none. Guarded by [lock], and cleared *before*
     * `waitpid` rather than after — see [kill] and [reap], where that ordering is the whole
     * pid-reuse defence. A plain [run] holds exactly one; a [runPipeline] holds one per stage.
     */
    private var childPids: List<Int> = emptyList()

    /** Guarded by [lock]. Records a [kill] that arrived before there was a child to kill. */
    private var killRequested = false

    // Named argument, not a trailing lambda into the last slot: both parameters are functional, and
    // naming them is what stops a future one from silently stealing the block.
    actual suspend fun run(
        command: List<String>,
        onStderr: (String) -> Unit,
    ): Int = runPipeline(listOf(command), onStderr)

    actual suspend fun runPipeline(
        commands: List<List<String>>,
        onStderr: (String) -> Unit,
    ): Int = runKillingChildOnCancellation(killChild = ::kill) { runToCompletion(commands, onStderr) }

    actual suspend fun awaitStarted() {
        started.await()
    }

    actual fun kill() {
        synchronized(lock) {
            killRequested = true
            // Signalling *inside* the lock is what closes the pid-reuse race. `waitpid` is what
            // lets the kernel recycle a pid, and [reap] clears `childPid` under this same lock
            // before it waits — so a pid still visible here is one the kernel has not been allowed
            // to hand to anybody else yet.
            childPids.forEach { posixKill(it, SIGKILL) }
        }
    }

    private fun runToCompletion(
        commands: List<List<String>>,
        onStderr: (String) -> Unit,
    ): Int {
        require(commands.isNotEmpty()) { "a pipeline needs at least one stage" }
        val spawned = spawn(commands)
        if (spawned !is Spawn.Started) {
            started.complete(Unit)
            return if (spawned is Spawn.BinaryNotFound) MISSING_BINARY_EXIT_CODE else SPAWN_FAILED_EXIT_CODE
        }
        adopt(spawned.pids)
        started.complete(Unit)

        // Every stage shares one stderr pipe, so this returns only once the last of them has closed
        // its end — which is exactly when there is nothing left to read from any of them.
        readStderr(spawned.stderrReadFd, onStderr)
        return reap(spawned.pids)
    }

    /** Takes ownership of [pids], honouring a [kill] that arrived while they were being started. */
    private fun adopt(pids: List<Int>) {
        synchronized(lock) {
            childPids = pids
            if (killRequested) pids.forEach { posixKill(it, SIGKILL) }
        }
    }

    /**
     * Forks one child per stage, wiring stage *i*'s stdout to stage *i+1*'s stdin through an OS
     * pipe. No shell: the pipes are created here and handed to the children directly.
     *
     * ⛔ **Every allocation happens before the first `fork`.** The argv arrays, the resolved paths
     * and all descriptors are built up front, because a forked child may call only async-signal-safe
     * functions — and allocating in one would run the caller's whole stack twice.
     *
     * All stages share a single stderr pipe. One reader then drains the lot, and it reaches EOF
     * exactly when the last stage has closed its end.
     */
    private fun spawn(commands: List<List<String>>): Spawn {
        val executables = commands.map { resolveExecutable(it.first()) ?: return Spawn.BinaryNotFound }
        return memScoped {
            val stderrFds = allocArray<IntVar>(2)
            if (pipe(stderrFds) != 0) {
                log.error { "pipe() failed (errno $errno) spawning ${commands.first().first()}" }
                return@memScoped Spawn.Failed
            }
            val stderrRead = stderrFds[0]
            val stderrWrite = stderrFds[1]

            // Opened in the parent: `open` takes a path, and marshalling a Kotlin String into a C
            // string allocates — the one thing the forked child must never do.
            val devNullFd = open("/dev/null", O_RDWR)
            if (devNullFd < 0) {
                log.error { "open(/dev/null) failed (errno $errno) spawning ${commands.first().first()}" }
                close(stderrRead)
                close(stderrWrite)
                return@memScoped Spawn.Failed
            }

            val joins = openJoinPipes(commands.size - 1)
            if (joins.opened < joins.count) {
                closeAll(stderrRead, stderrWrite, devNullFd, joins.readEnds, joins.writeEnds, joins.opened)
                return@memScoped Spawn.Failed
            }

            val plans =
                commands.mapIndexed { index, command ->
                    ChildPlan(
                        argv = argv(command),
                        executablePath = executables[index].cstr.getPointer(this),
                        stdinFd = if (index == 0) devNullFd else joins.readEnds[index - 1],
                        stdoutFd = if (index == commands.lastIndex) devNullFd else joins.writeEnds[index],
                        stderrWriteFd = stderrWrite,
                        highestFd = highestFd(),
                    )
                }

            val pids = forkStages(plans)
            if (pids == null) {
                closeAll(stderrRead, stderrWrite, devNullFd, joins.readEnds, joins.writeEnds, joins.opened)
                return@memScoped Spawn.Failed
            }

            // The parent keeps only the stderr read end. Holding a write end open would stop the
            // downstream stage ever seeing EOF, and the pipeline would hang forever.
            close(stderrWrite)
            close(devNullFd)
            for (i in 0 until joins.count) {
                close(joins.readEnds[i])
                close(joins.writeEnds[i])
            }
            Spawn.Started(pids, stderrRead)
        }
    }

    /** The [count] pipes joining consecutive stages, and how many were actually opened. */
    private class JoinPipes(
        val readEnds: IntArray,
        val writeEnds: IntArray,
        val count: Int,
        val opened: Int,
    )

    /**
     * Opens one pipe per join. Stops at the first failure rather than unwinding here, so the caller
     * closes exactly what was opened — [JoinPipes.opened] is that count.
     */
    private fun MemScope.openJoinPipes(count: Int): JoinPipes {
        val readEnds = IntArray(count)
        val writeEnds = IntArray(count)
        var opened = 0
        while (opened < count) {
            val fds = allocArray<IntVar>(2)
            if (pipe(fds) != 0) {
                log.error { "pipe() failed (errno $errno) joining pipeline stage $opened" }
                break
            }
            readEnds[opened] = fds[0]
            writeEnds[opened] = fds[1]
            opened++
        }
        return JoinPipes(readEnds, writeEnds, count, opened)
    }

    /**
     * Forks one child per plan, or null if any fork fails — in which case the stages already
     * running are killed and reaped first, since orphaning them would leave pipe ends held open.
     */
    private fun forkStages(plans: List<ChildPlan>): List<Int>? {
        val pids = mutableListOf<Int>()
        for (plan in plans) {
            when (val pid = fork()) {
                -1 -> {
                    log.error { "fork() failed (errno $errno) spawning a pipeline stage" }
                    pids.forEach { posixKill(it, SIGKILL) }
                    pids.forEach { reapOne(it) }
                    return null
                }

                0 -> {
                    becomeChild(plan)
                }

                else -> {
                    pids += pid
                }
            }
        }
        return pids
    }

    private fun closeAll(
        stderrRead: Int,
        stderrWrite: Int,
        devNullFd: Int,
        readEnds: IntArray,
        writeEnds: IntArray,
        opened: Int,
    ) {
        close(stderrRead)
        close(stderrWrite)
        close(devNullFd)
        for (i in 0 until opened) {
            close(readEnds[i])
            close(writeEnds[i])
        }
    }

    /**
     * Runs in the forked child and never returns: `dup2`, the descriptor sweep, `execv`, `_exit`.
     * Every value it touches was built before the fork, so it allocates nothing.
     */
    private fun becomeChild(plan: ChildPlan): Nothing {
        dup2(plan.stdinFd, STDIN_FILENO)
        dup2(plan.stdoutFd, STDOUT_FILENO)
        dup2(plan.stderrWriteFd, STDERR_FILENO)
        // Runs *after* the dup2s, which is the whole ordering constraint: the sweep closes the
        // originals it just copied down — every pipe end and /dev/null — along with every socket
        // and file the server had open. Stdin, stdout and stderr survive it.
        lu_close_fds_from(FIRST_INHERITABLE_FD, plan.highestFd)
        execv(plan.executablePath, plan.argv)
        // ⚠️ Known parity gap with the JVM actual, and an unavoidable one. Reaching this line means
        // `execv` failed *after* the parent's `access(X_OK)` said it would not — a permission change
        // between the two, or ENOEXEC — which the JVM actual would log and report as
        // SPAWN_FAILED_EXIT_CODE. Here it is indistinguishable from "not installed", because nothing
        // in a forked child may allocate, and logging allocates.
        _exit(MISSING_BINARY_EXIT_CODE)
        error("unreachable: _exit does not return")
    }

    private fun MemScope.argv(command: List<String>): CPointer<CPointerVar<ByteVar>> {
        val argv = allocArray<CPointerVar<ByteVar>>(command.size + 1)
        for (i in command.indices) argv[i] = command[i].cstr.getPointer(this)
        argv[command.size] = null
        return argv
    }

    /**
     * Resolves [name] to an absolute path so the child can call the async-signal-safe `execv`
     * instead of the `$PATH`-searching (and thus allocating) `execvp`. A name already containing a
     * `/` is used as-is — its resolution, or lack of it, is deferred to `execv` in the child, same
     * as before this change. A bare name is resolved by walking `$PATH` here in the parent, where
     * allocation is unremarkable; `null` means nothing on `$PATH` was executable, so [spawn] returns
     * without forking at all.
     */
    private fun resolveExecutable(name: String): String? {
        if (name.contains('/')) return name
        val path = getenv("PATH")?.toKString() ?: return null
        for (dir in path.split(':')) {
            if (dir.isEmpty()) continue
            val candidate = "$dir/$name"
            if (access(candidate, X_OK) == 0) return candidate
        }
        return null
    }

    /** The parent's descriptor ceiling, read before the fork because `sysconf` may allocate. */
    private fun highestFd(): UInt {
        val limit = sysconf(_SC_OPEN_MAX)
        return if (limit <= 0) MAX_FD_SCAN.toUInt() else minOf(limit, MAX_FD_SCAN).toUInt()
    }

    private fun readStderr(
        fd: Int,
        onStderr: (String) -> Unit,
    ) {
        val buffer = ByteArray(READ_BUFFER_BYTES)
        val pending = StringBuilder()
        while (true) {
            val n = buffer.usePinned { pinned -> read(fd, pinned.addressOf(0), buffer.size.convert()).toInt() }
            // A signal can interrupt a blocking read before any byte arrives. Treating that as EOF
            // would truncate the child's diagnostics and reap it early.
            if (n < 0 && errno == EINTR) continue
            if (n <= 0) break
            for (i in 0 until n) {
                val b = buffer[i]
                if (b == NEWLINE) {
                    onStderr(pending.toString())
                    pending.clear()
                } else {
                    pending.append(b.toInt().toChar())
                }
            }
        }
        if (pending.isNotEmpty()) onStderr(pending.toString())
        close(fd)
    }

    /**
     * Waits for every stage and returns the **first non-zero** exit code — pipefail, not the
     * shell's last-stage-only answer. See the expect declaration for why that difference matters.
     */
    private fun reap(pids: List<Int>): Int {
        synchronized(lock) { childPids = emptyList() }
        return pids.map { reapOne(it) }.firstOrNull { it != 0 } ?: 0
    }

    private fun reapOne(pid: Int): Int {
        // The pid list was cleared *before* this wait, under the lock: `waitpid` is what frees a
        // pid for reuse, so no `kill` may hold one across that moment. A kill arriving from here on
        // finds an empty list and does nothing — correct, because stderr reaching EOF already means
        // every child is gone.
        return memScoped {
            val statusVar = alloc<IntVar>()
            while (waitpid(pid, statusVar.ptr, 0) < 0 && errno == EINTR) {
                // A signal interrupted the wait; the child has not been reaped yet, so wait again.
            }
            val status = statusVar.value
            if (status and WAIT_STATUS_SIGNAL_MASK == 0) {
                (status shr WAIT_STATUS_EXIT_SHIFT) and WAIT_STATUS_EXIT_MASK
            } else {
                SIGNALED_EXIT_BASE + (status and WAIT_STATUS_SIGNAL_MASK)
            }
        }
    }

    /**
     * Everything the child needs, built in the parent so the child itself allocates nothing —
     * [highestFd] included, since `sysconf` is not on POSIX's async-signal-safe list either.
     */
    private class ChildPlan(
        val argv: CPointer<CPointerVar<ByteVar>>,
        val executablePath: CPointer<ByteVar>,
        val stdinFd: Int,
        val stdoutFd: Int,
        val stderrWriteFd: Int,
        val highestFd: UInt,
    )

    private sealed interface Spawn {
        class Started(
            val pids: List<Int>,
            val stderrReadFd: Int,
        ) : Spawn

        /** Nothing executable by that name — the answer the provisioner's boot probe is asking for. */
        data object BinaryNotFound : Spawn

        /** A `pipe`/`open`/`fork` syscall failed. Operational, already logged with its errno. */
        data object Failed : Spawn
    }

    private companion object {
        const val NEWLINE: Byte = '\n'.code.toByte()
    }
}
