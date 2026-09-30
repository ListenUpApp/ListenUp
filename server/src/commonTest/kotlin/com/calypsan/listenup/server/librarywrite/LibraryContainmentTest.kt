package com.calypsan.listenup.server.librarywrite

import com.calypsan.listenup.api.error.LibraryWriteError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.server.io.isSymlink
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory
import kotlinx.io.readByteArray
import kotlin.random.Random

/**
 * Containment on real directories and real symbolic links, on the JVM and on the native server.
 *
 * `LibraryWriteBrokerContainmentTest` (jvmTest) pins the first escapes found. This spec is the
 * wider sweep, in commonTest so the Kotlin/Native build — the one that actually ships — resolves
 * links through its own `realpath` and is held to the same answers.
 *
 * Every broker here is given a **narrow** root (the fixture's own `library/`), with the escape
 * target in a sibling `outside/`. Under a broad allow-list the escapes would land inside it and
 * the refusals would prove nothing. Refusals are asserted as [LibraryWriteError.OutsideLibrary]
 * specifically, not just "a failure": an I/O error that happens to stop a write is luck, and the
 * point of the guard is that it does not depend on luck.
 *
 * One rule runs through the symlink cases. Unlink and rename act on the link itself, so for ops
 * built on them — writes, moves, file deletes — a link as the **final** segment is judged where it
 * sits, and so is delete-if-empty, which leaves a link alone. Ops that act *through* a link (ensure,
 * recursive delete) judge it where it points. A link anywhere *before* the final segment is always
 * followed.
 */
class LibraryContainmentTest :
    FunSpec({

        /** `<base>/library` (the root), `<base>/outside` (the rest of the disk), and a user's file out there. */
        class Fixture(
            val base: Path,
        ) {
            val root = Path(base, "library").also { SystemFileSystem.createDirectories(it) }
            val outside = Path(base, "outside").also { SystemFileSystem.createDirectories(it) }
            val victim = Path(outside, "precious.txt").also { plant(it, USER_DATA) }

            fun broker(roots: List<Path> = listOf(root)): LibraryWriteBroker =
                LibraryWriteBroker(
                    registry = SelfWriteRegistry { 0L },
                    journal = WriteJournal(Path(base, "journal")),
                    libraryRoots = { roots },
                )
        }

        fun fixture(): Fixture =
            Fixture(
                Path(SystemTemporaryDirectory, "containment-${Random.nextLong().toULong().toString(16)}")
                    .also { SystemFileSystem.createDirectories(it) },
            )

        suspend fun LibraryWriteBroker.perform(vararg ops: WriteOp): AppResult<Unit> =
            executeManifest(WriteManifest(opId = "containment-${Random.nextLong().toULong()}", ops = ops.toList()))

        fun AppResult<*>.shouldBeOutsideLibrary() {
            val failure = shouldBeInstanceOf<AppResult.Failure>()
            failure.error.shouldBeInstanceOf<LibraryWriteError.OutsideLibrary>()
        }

        test("CONTROL — a legitimate write inside the root lands") {
            val f = fixture()
            val target = Path(f.root, "Book", "listenup.json")

            f.broker().writeFile(target, CLOBBER).shouldBeInstanceOf<AppResult.Success<WrittenFile>>()

            readAll(target) shouldBe CLOBBER
        }

        context("`..` traversal") {
            test("a target climbing out through `..` is refused") {
                val f = fixture()
                SystemFileSystem.createDirectories(Path(f.root, "Book"))

                f
                    .broker()
                    .writeFile(Path(f.root, "Book", "..", "..", "outside", "precious.txt"), CLOBBER)
                    .shouldBeOutsideLibrary()

                readAll(f.victim) shouldBe USER_DATA
            }

            test("`..` after a symlink climbs from where the link POINTS, as the kernel does") {
                // <root>/Link -> <outside>/Deep, so <root>/Link/.. is <outside>, not <root>. Folding
                // the `..` away as text first reads the path as <root>/precious.txt and waves it in.
                val f = fixture()
                val deep = Path(f.outside, "Deep").also { SystemFileSystem.createDirectories(it) }
                createSymbolicLink(Path(f.root, "Link"), deep)

                f.broker().writeFile(Path(f.root, "Link", "..", "precious.txt"), CLOBBER).shouldBeOutsideLibrary()

                withClue("the user's file the kernel would really have written must be untouched") {
                    readAll(f.victim) shouldBe USER_DATA
                }
            }

            test("`..` that stays inside the root is not an escape") {
                val f = fixture()
                SystemFileSystem.createDirectories(Path(f.root, "Book"))
                val target = Path(f.root, "Book", "..", "Other", "listenup.json")

                f.broker().writeFile(target, CLOBBER).shouldBeInstanceOf<AppResult.Success<WrittenFile>>()

                readAll(Path(f.root, "Other", "listenup.json")) shouldBe CLOBBER
            }
        }

        context("symlinked directories") {
            test("a write through a book directory linked outside the root is refused") {
                val f = fixture()
                val real = Path(f.outside, "RealBook").also { SystemFileSystem.createDirectories(it) }
                createSymbolicLink(Path(f.root, "Book"), real)

                f.broker().writeFile(Path(f.root, "Book", "listenup.json"), CLOBBER).shouldBeOutsideLibrary()

                SystemFileSystem.exists(Path(real, "listenup.json")) shouldBe false
            }

            test("a not-yet-existing target beneath a linked directory is refused, and nothing is created out there") {
                val f = fixture()
                val real = Path(f.outside, "RealBook").also { SystemFileSystem.createDirectories(it) }
                createSymbolicLink(Path(f.root, "Book"), real)

                f
                    .broker()
                    .writeFile(Path(f.root, "Book", "Disc 1", "Extras", "listenup.json"), CLOBBER)
                    .shouldBeOutsideLibrary()

                SystemFileSystem.exists(Path(real, "Disc 1")) shouldBe false
            }

            test("a dangling link in the path is refused rather than guessed at") {
                // Nothing to resolve it against yet — the day its target appears, the same path
                // leads outside. Refusing now is the only answer that stays right.
                val f = fixture()
                val notYet = Path(f.outside, "NotYet")
                createSymbolicLink(Path(f.root, "Book"), notYet)

                f.broker().writeFile(Path(f.root, "Book", "listenup.json"), CLOBBER).shouldBeOutsideLibrary()

                SystemFileSystem.exists(notYet) shouldBe false
            }

            test("a symlink loop is refused as unresolvable, not left to fail by accident") {
                val f = fixture()
                val a = Path(f.root, "A")
                val b = Path(f.root, "B")
                createSymbolicLink(a, b)
                createSymbolicLink(b, a)

                f.broker().writeFile(Path(a, "listenup.json"), CLOBBER).shouldBeOutsideLibrary()
            }

            test("a delete-if-empty of a directory link leaves the link alone — a link is not an empty directory") {
                // DeleteDirIfEmpty prunes directories a delete or move emptied. A link is not one,
                // whatever it points at: the op neither follows it (refusing, or emptying the target)
                // nor unlinks it (removing something the user made). It is simply not its business.
                val f = fixture()
                val emptyOutside = Path(f.outside, "EmptyBook").also { SystemFileSystem.createDirectories(it) }
                val link = Path(f.root, "Book")
                createSymbolicLink(link, emptyOutside)

                f.broker().perform(WriteOp.DeleteDirIfEmpty(link)).shouldBeInstanceOf<AppResult.Success<Unit>>()

                withClue("the user's link survives") { isSymlink(link) shouldBe true }
                withClue("and so does the directory it points at") { SystemFileSystem.exists(emptyOutside) shouldBe true }
            }

            test("a relative link climbing out of the root is followed like any other") {
                val f = fixture()
                val real = Path(f.outside, "RealBook").also { SystemFileSystem.createDirectories(it) }
                SystemFileSystem.createDirectories(Path(f.root, "Author"))
                // <root>/Author/Book -> ../../outside/RealBook, resolved relative to the link's own directory.
                createSymbolicLink(Path(f.root, "Author", "Book"), Path("../../outside/RealBook"))

                f.broker().writeFile(Path(f.root, "Author", "Book", "listenup.json"), CLOBBER).shouldBeOutsideLibrary()

                SystemFileSystem.exists(Path(real, "listenup.json")) shouldBe false
            }

            test("`..` after a MISSING segment cannot smuggle a later link past resolution") {
                // <root>/Missing/../Link/x: Missing does not exist, so the existing prefix is <root>
                // and everything after it is "tail". Folding that tail as text gives <root>/Link/x and
                // never resolves Link — which points outside. A tail holding `..` is refused outright.
                val f = fixture()
                val real = Path(f.outside, "RealBook").also { SystemFileSystem.createDirectories(it) }
                createSymbolicLink(Path(f.root, "Link"), real)

                f
                    .broker()
                    .writeFile(Path(f.root, "Missing", "..", "Link", "listenup.json"), CLOBBER)
                    .shouldBeOutsideLibrary()

                SystemFileSystem.exists(Path(real, "listenup.json")) shouldBe false
            }
        }

        context("moves check both ends") {
            test("a move whose source is outside the root is refused") {
                val f = fixture()
                val stolen = Path(f.root, "stolen.txt")

                f.broker().perform(WriteOp.MoveFile(f.victim, stolen)).shouldBeOutsideLibrary()

                readAll(f.victim) shouldBe USER_DATA
                SystemFileSystem.exists(stolen) shouldBe false
            }

            test("a move whose destination is outside the root is refused") {
                val f = fixture()
                val track = Path(f.root, "Book", "01.mp3").also { plant(it, USER_DATA) }
                val away = Path(f.outside, "01.mp3")

                f.broker().perform(WriteOp.MoveFile(track, away)).shouldBeOutsideLibrary()

                readAll(track) shouldBe USER_DATA
                SystemFileSystem.exists(away) shouldBe false
            }

            test("a move whose destination sits beneath a linked-out directory is refused") {
                val f = fixture()
                val track = Path(f.root, "Book", "01.mp3").also { plant(it, USER_DATA) }
                val real = Path(f.outside, "RealBook").also { SystemFileSystem.createDirectories(it) }
                createSymbolicLink(Path(f.root, "Linked"), real)

                f.broker().perform(WriteOp.MoveFile(track, Path(f.root, "Linked", "01.mp3"))).shouldBeOutsideLibrary()

                readAll(track) shouldBe USER_DATA
                SystemFileSystem.exists(Path(real, "01.mp3")) shouldBe false
            }
        }

        context("deleting a symlink entry vs following it") {
            test("deleting a link that sits inside the root removes the link, never its target") {
                val f = fixture()
                val link = Path(f.root, "Book", "cover.jpg")
                SystemFileSystem.createDirectories(Path(f.root, "Book"))
                createSymbolicLink(link, f.victim)

                f.broker().perform(WriteOp.DeleteFile(link)).shouldBeInstanceOf<AppResult.Success<Unit>>()

                withClue("the link entry itself is gone") { isSymlink(link) shouldBe false }
                withClue("and the file it pointed at, outside the library, is untouched") {
                    readAll(f.victim) shouldBe USER_DATA
                }
            }

            test("a DANGLING link inside the root is deleted, not reported deleted and left behind") {
                val f = fixture()
                val link = Path(f.root, "Book", "cover.jpg")
                SystemFileSystem.createDirectories(Path(f.root, "Book"))
                createSymbolicLink(link, Path(f.outside, "long-gone.jpg"))

                f.broker().perform(WriteOp.DeleteFile(link)).shouldBeInstanceOf<AppResult.Success<Unit>>()

                isSymlink(link) shouldBe false
            }

            test("a recursive delete removes dangling and looping links inside the book, and finishes") {
                // A stale cover.jpg link used to survive the walk (kotlinx-io's delete asks exists(),
                // which follows links), the final rmdir failed ENOTEMPTY, and Delete Book was left
                // with its audio gone and its folder unremovable, failing on every retry.
                val f = fixture()
                val bookDir = Path(f.root, "A Book")
                plant(Path(bookDir, "01.m4b"), CLOBBER)
                createSymbolicLink(Path(bookDir, "cover.jpg"), Path(f.outside, "long-gone.jpg"))
                createSymbolicLink(Path(bookDir, "loop"), Path(bookDir, "loop"))

                f.broker().perform(WriteOp.DeleteDir(bookDir)).shouldBeInstanceOf<AppResult.Success<Unit>>()

                withClue("the book directory is gone, links and all") {
                    SystemFileSystem.exists(bookDir) shouldBe false
                    isSymlink(bookDir) shouldBe false
                }
            }

            test("a recursive delete unlinks a directory link inside the book and never walks through it") {
                val f = fixture()
                val photos = Path(f.outside, "Photos")
                val wedding = Path(photos, "wedding.jpg").also { plant(it, USER_DATA) }
                val bookDir = Path(f.root, "A Book")
                plant(Path(bookDir, "01.m4b"), CLOBBER)
                createSymbolicLink(Path(bookDir, "photos"), photos)

                f.broker().perform(WriteOp.DeleteDir(bookDir)).shouldBeInstanceOf<AppResult.Success<Unit>>()

                SystemFileSystem.exists(bookDir) shouldBe false
                withClue("the linked-to directory is not part of the book and must survive intact") {
                    readAll(wedding) shouldBe USER_DATA
                }
            }

            test("deleting a file THROUGH a linked-out directory is refused") {
                val f = fixture()
                createSymbolicLink(Path(f.root, "Book"), f.outside)

                f.broker().perform(WriteOp.DeleteFile(Path(f.root, "Book", "precious.txt"))).shouldBeOutsideLibrary()

                readAll(f.victim) shouldBe USER_DATA
            }
        }

        context("multiple roots") {
            test("writes land in every live root, a move between two roots is allowed, and a neighbour is not a root") {
                val f = fixture()
                val second = Path(f.base, "library-two").also { SystemFileSystem.createDirectories(it) }
                // Shares `library` as a string prefix with the first root — a raw prefix test says "inside".
                val lookalike = Path(f.base, "library-extra").also { SystemFileSystem.createDirectories(it) }
                val broker = f.broker(roots = listOf(f.root, second))

                val inFirst = Path(f.root, "Book", "01.mp3")
                broker.writeFile(inFirst, CLOBBER).shouldBeInstanceOf<AppResult.Success<WrittenFile>>()
                broker
                    .writeFile(Path(second, "Book", "listenup.json"), CLOBBER)
                    .shouldBeInstanceOf<AppResult.Success<WrittenFile>>()

                val inSecond = Path(second, "Moved", "01.mp3")
                broker
                    .perform(WriteOp.EnsureDir(Path(second, "Moved")), WriteOp.MoveFile(inFirst, inSecond))
                    .shouldBeInstanceOf<AppResult.Success<Unit>>()
                readAll(inSecond) shouldBe CLOBBER

                broker.writeFile(Path(lookalike, "listenup.json"), CLOBBER).shouldBeOutsideLibrary()
                SystemFileSystem.exists(Path(lookalike, "listenup.json")) shouldBe false
            }

            test("a refusal names the offending path, never the server's library roots") {
                // debugInfo travels back to the client (Delete Book returns it). The roots belong in
                // the server log, not in the hands of anyone who can press delete.
                val f = fixture()
                val second = Path(f.base, "library-two").also { SystemFileSystem.createDirectories(it) }
                val escaping = Path(f.outside, "listenup.json")

                val failure =
                    f
                        .broker(roots = listOf(f.root, second))
                        .writeFile(escaping, CLOBBER)
                        .shouldBeInstanceOf<AppResult.Failure>()

                val debugInfo = failure.error.debugInfo.orEmpty()
                debugInfo shouldContain escaping.toString()
                debugInfo shouldNotContain f.root.toString()
                debugInfo shouldNotContain second.toString()
            }

            test("with no library folders configured, nothing is writable") {
                val f = fixture()

                f.broker(roots = emptyList()).writeFile(Path(f.root, "listenup.json"), CLOBBER).shouldBeOutsideLibrary()
            }
        }

        context("the root itself") {
            test("the root is inside itself — ensuring it succeeds — but it can never be removed") {
                val f = fixture()
                val broker = f.broker()

                broker.perform(WriteOp.EnsureDir(f.root)).shouldBeInstanceOf<AppResult.Success<Unit>>()

                val removal = broker.perform(WriteOp.DeleteDirIfEmpty(f.root)).shouldBeInstanceOf<AppResult.Failure>()
                removal.error.shouldBeInstanceOf<LibraryWriteError.ProtectedPath>()
                SystemFileSystem.exists(f.root) shouldBe true
            }

            test("an empty root cannot be removed by a DeleteFile either") {
                // Deleting an empty directory entry is what unlink-or-rmdir does; for a root that
                // would unmake the library folder, whichever op asked.
                val f = fixture()

                val removal =
                    f.broker().perform(WriteOp.DeleteFile(f.root)).shouldBeInstanceOf<AppResult.Failure>()

                removal.error.shouldBeInstanceOf<LibraryWriteError.ProtectedPath>()
                SystemFileSystem.exists(f.root) shouldBe true
            }

            test("a root configured through a symlink accepts writes by either spelling") {
                // `/srv/audiobooks -> /mnt/drive/audiobooks` is an ordinary way to configure a library.
                val f = fixture()
                val alias = Path(f.base, "library-alias")
                createSymbolicLink(alias, f.root)
                val broker = f.broker(roots = listOf(alias))

                broker.writeFile(Path(alias, "Book", "a.json"), CLOBBER).shouldBeInstanceOf<AppResult.Success<WrittenFile>>()
                broker.writeFile(Path(f.root, "Book", "b.json"), CLOBBER).shouldBeInstanceOf<AppResult.Success<WrittenFile>>()
                broker.writeFile(Path(f.outside, "c.json"), CLOBBER).shouldBeOutsideLibrary()
            }
        }
    })

private val USER_DATA = "USER DATA — NOT OURS".encodeToByteArray()
private val CLOBBER = "CLOBBERED BY THE BROKER".encodeToByteArray()

private fun readAll(path: Path): ByteArray = SystemFileSystem.source(path).buffered().use { it.readByteArray() }

private fun plant(
    path: Path,
    bytes: ByteArray,
) {
    path.parent?.let { SystemFileSystem.createDirectories(it) }
    SystemFileSystem.sink(path).buffered().use { it.write(bytes) }
}
