@file:OptIn(ExperimentalForeignApi::class)

package com.calypsan.listenup.server.process

import com.calypsan.listenup.server.io.fileIoDispatcher
import kotlinx.atomicfu.atomic
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointerVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.convert
import kotlinx.cinterop.cstr
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.set
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import kotlinx.cinterop.toKString
import platform.posix.SIGKILL
import platform.posix.STDERR_FILENO
import platform.posix.X_OK
import platform.posix._exit
import platform.posix.access
import platform.posix.close
import platform.posix.dup2
import platform.posix.fork
import platform.posix.getenv
import platform.posix.kill as posixKill
import platform.posix.pipe
import platform.posix.read
import platform.posix.waitpid
import rawexec.execv

/** A missing/unresolvable binary is a normal outcome (the provisioner probes for one), not a crash. */
private const val MISSING_BINARY_EXIT_CODE = 127

/** Shell-style "128 + signal" exit code reported when `waitpid` says the child died from a signal. */
private const val SIGNALED_EXIT_BASE = 128

/** Low 7 bits of a `waitpid` status: zero means the child exited normally (glibc's `WIFEXITED`). */
private const val WAIT_STATUS_SIGNAL_MASK = 0x7f

/** The exit code lives in the next 8 bits up (glibc's `WEXITSTATUS`). */
private const val WAIT_STATUS_EXIT_SHIFT = 8
private const val WAIT_STATUS_EXIT_MASK = 0xff

private const val READ_BUFFER_BYTES = 4096

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
 * straight through. With that, the child does nothing but `dup2`/`close`/`execv`/`_exit` — and never
 * returns into Kotlin/coroutine machinery, since that would run the caller's whole stack twice.
 */
actual class ProcessRunner {
    private val started = CompletableDeferred<Unit>()
    private val childPid = atomic(0)

    actual suspend fun run(
        command: List<String>,
        onStderr: (String) -> Unit,
    ): Int =
        withContext(fileIoDispatcher) {
            // If the surrounding coroutine is cancelled while the child is still running, kill it —
            // an abandoned transcode must not leak a running ffmpeg process.
            currentCoroutineContext().job.invokeOnCompletion { kill() }

            val spawned = spawn(command)
            if (spawned == null) {
                started.complete(Unit)
                return@withContext MISSING_BINARY_EXIT_CODE
            }
            childPid.value = spawned.pid
            started.complete(Unit)

            readStderr(spawned.stderrReadFd, onStderr)
            reap(spawned.pid)
        }

    actual suspend fun awaitStarted() {
        started.await()
    }

    actual fun kill() {
        val pid = childPid.value
        if (pid != 0) posixKill(pid, SIGKILL)
    }

    /**
     * `null` means either the executable couldn't be resolved on `$PATH`, or the fork itself failed
     * (pipe/fork syscall error) — both are treated like a missing binary.
     */
    private fun spawn(command: List<String>): Spawned? {
        val executablePath = resolveExecutable(command[0]) ?: return null
        return memScoped {
            val fds = allocArray<IntVar>(2)
            if (pipe(fds) != 0) return null
            val readFd = fds[0]
            val writeFd = fds[1]

            // Build argv and the resolved exec path before forking so the child touches no
            // Kotlin/Native allocator itself — only pre-built pointers and syscalls.
            val argv = allocArray<CPointerVar<ByteVar>>(command.size + 1)
            for (i in command.indices) argv[i] = command[i].cstr.getPointer(this)
            argv[command.size] = null
            val execPath = executablePath.cstr.getPointer(this)

            when (val pid = fork()) {
                -1 -> {
                    close(readFd)
                    close(writeFd)
                    null
                }

                0 -> {
                    close(readFd)
                    dup2(writeFd, STDERR_FILENO)
                    close(writeFd)
                    execv(execPath, argv)
                    _exit(MISSING_BINARY_EXIT_CODE)
                    error("unreachable: _exit does not return")
                }

                else -> {
                    close(writeFd)
                    Spawned(pid, readFd)
                }
            }
        }
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

    private fun readStderr(
        fd: Int,
        onStderr: (String) -> Unit,
    ) {
        val buffer = ByteArray(READ_BUFFER_BYTES)
        val pending = StringBuilder()
        while (true) {
            val n = buffer.usePinned { pinned -> read(fd, pinned.addressOf(0), buffer.size.convert()).toInt() }
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

    private fun reap(pid: Int): Int =
        memScoped {
            val statusVar = alloc<IntVar>()
            waitpid(pid, statusVar.ptr, 0)
            childPid.value = 0
            val status = statusVar.value
            if (status and WAIT_STATUS_SIGNAL_MASK == 0) {
                (status shr WAIT_STATUS_EXIT_SHIFT) and WAIT_STATUS_EXIT_MASK
            } else {
                SIGNALED_EXIT_BASE + (status and WAIT_STATUS_SIGNAL_MASK)
            }
        }

    private class Spawned(
        val pid: Int,
        val stderrReadFd: Int,
    )

    private companion object {
        const val NEWLINE: Byte = '\n'.code.toByte()
    }
}
