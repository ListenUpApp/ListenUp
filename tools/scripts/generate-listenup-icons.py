#!/usr/bin/env python3
"""Generate ListenUpIcons.kt from Material Symbols Rounded SVGs.

Source: the Google material-design-icons repository (Apache-2.0), pinned at the commit below. Download
  symbols/web/<name>/materialsymbolsrounded/<name>_24px.svg        (FILL 0, outline)
  symbols/web/<name>/materialsymbolsrounded/<name>_fill1_24px.svg  (FILL 1, filled)
at that commit into a directory of their own (flat: <name>.svg, <name>_fill1.svg), then run:

  python3 -I tools/scripts/generate-listenup-icons.py <svg-dir> [<mapping>] [<output.kt>]

Pinned upstream commit: 10ecc14790612ca8fd164e3f4ffe9c16fc50d5b7
Bumping it is a deliberate visual change. Stdlib only. A mapped name with no SVG, or an SVG with more
than one plain <path>, fails the run; nothing is guessed.
"""

import re
import sys
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
DEFAULT_MAPPING = REPO / "tools/scripts/listenup-icons.txt"
DEFAULT_OUTPUT = (
    REPO / "app/sharedUI/src/commonMain/kotlin/com/calypsan/listenup/client/design/icons/ListenUpIcons.kt"
)
PATH_RE = re.compile(r'<path\s+d="([^"]+)"\s*/>')
WRAP = 100

HEADER = """\
// Generated -- do not edit. Run tools/scripts/generate-listenup-icons.py.
// Glyphs: Material Symbols Rounded, weight 400, grade 0, optical size 24 (Apache-2.0),
// Google material-design-icons @ 10ecc14790612ca8fd164e3f4ffe9c16fc50d5b7.
package com.calypsan.listenup.client.design.icons

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * The app's glyphs, drawn from Material Symbols Rounded. `X` is the outline (FILL 0) and `XFilled` the filled
 * (FILL 1) form; directional glyphs mirror in right-to-left layouts.
 */
object ListenUpIcons {
"""

FOOTER = """\
}

private const val SYMBOLS_VIEWPORT = 960f

/** Symbols draw in a `0 -960 960 960` viewBox; the group shifts it into Compose's `0 0 960 960`. */
private fun symbol(
    name: String,
    autoMirror: Boolean,
    pathData: String,
): ImageVector =
    ImageVector
        .Builder(
            name = name,
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = SYMBOLS_VIEWPORT,
            viewportHeight = SYMBOLS_VIEWPORT,
            autoMirror = autoMirror,
        ).apply {
            addGroup(translationY = SYMBOLS_VIEWPORT)
            addPath(pathData = addPathNodes(pathData), fill = SolidColor(Color.Black))
            clearGroup()
        }.build()
"""


def wrap(d: str, seen: set[str]) -> list[str]:
    """Split path data into pieces of about WRAP characters; the pieces are concatenated, so any boundary is safe.

    The width shrinks until no piece repeats one already written, because detekt flags a repeated string literal
    and an outline and its filled sibling usually open with the same stretch of path.
    """
    for width in range(WRAP, WRAP - 40, -1):
        pieces = [d[i : i + width] for i in range(0, len(d), width)]
        if not seen.intersection(pieces) and len(set(pieces)) == len(pieces):
            break
    else:
        sys.exit("could not split path data without repeating a literal")
    seen.update(pieces)
    return pieces


def read_mapping(path: Path) -> list[tuple[str, str, int, bool]]:
    rows = []
    for raw in path.read_text().splitlines():
        line = raw.strip()
        if not line or line.startswith("#"):
            continue
        kotlin, symbol, fill, mirror = (cell.strip() for cell in line.split("|"))
        if fill not in ("0", "1") or mirror not in ("true", "false"):
            sys.exit(f"bad mapping line: {raw!r}")
        rows.append((kotlin, symbol, int(fill), mirror == "true"))
    names = [r[0] for r in rows]
    if len(set(names)) != len(names):
        sys.exit("duplicate Kotlin names in mapping")
    return rows


def main() -> None:
    if len(sys.argv) < 2:
        sys.exit(__doc__)
    svg_dir = Path(sys.argv[1])
    mapping = Path(sys.argv[2]) if len(sys.argv) > 2 else DEFAULT_MAPPING
    output = Path(sys.argv[3]) if len(sys.argv) > 3 else DEFAULT_OUTPUT

    body, constants, unmapped = [], {}, []
    for kotlin, symbol, fill, mirror in read_mapping(mapping):
        svg = svg_dir / (f"{symbol}_fill1.svg" if fill else f"{symbol}.svg")
        if not svg.is_file():
            unmapped.append(f"{kotlin} -> {svg.name}")
            continue
        text = svg.read_text()
        paths = PATH_RE.findall(text)
        if len(paths) != 1 or "transform" in text:
            sys.exit(f"{svg.name}: expected exactly one plain <path>, found {len(paths)}")
        const = f"PATH_{symbol.upper()}" + ("_FILL" if fill else "")
        constants[const] = paths[0]
        body.append(
            f"    val {kotlin}: ImageVector by lazy {{\n"
            f'        symbol(name = "{kotlin}", autoMirror = {str(mirror).lower()}, pathData = {const})\n'
            f"    }}\n"
        )
    if unmapped:
        sys.exit("unmapped:\n  " + "\n  ".join(unmapped))
    pieces, seen = [], set()
    for const, d in constants.items():
        literal = " +\n".join(f'    "{c}"' for c in wrap(d, seen)).lstrip()
        pieces.append(f"private const val {const} =\n    {literal}\n")
    output.write_text(HEADER + "\n".join(body) + FOOTER + "\n" + "\n".join(pieces))
    print(f"wrote {len(body)} icons to {output}")


if __name__ == "__main__":
    main()
