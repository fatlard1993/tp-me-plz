#!/usr/bin/env python3
"""Generate the inventory button's mark: an ender pearl, 10x10 with a baked drop shadow.

Under textures/gui/sprites/, so it is the GUI atlas sprite "tp-me-plz:icon_pearl" that the
button names. Same canvas and shadow as Chest Utils' marks, which sit on the same header row,
but in the pearl's own colours: the one picture of teleporting every player already knows,
for players still learning to read.

Deterministic, stdlib-only.
"""

import os
import struct
import zlib

HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(HERE, "src/main/resources/assets/tp-me-plz/textures/gui/sprites")

COLOURS = {
    "D": (0x0C, 0x4A, 0x3E, 0xFF),  # rim
    "P": (0x25, 0x84, 0x74, 0xFF),  # body
    "L": (0x3D, 0xB8, 0x9C, 0xFF),  # lit side
    "H": (0xC8, 0xFF, 0xEE, 0xFF),  # glint
}
SHADOW = (0x3F, 0x3F, 0x3F, 0xFF)
CLEAR = (0, 0, 0, 0)

# 9x9 art on a 10x10 canvas, so the +1/+1 shadow always has room.
PEARL = [
    "...DDD...",
    ".DDLLPDD.",
    ".DLHLPPD.",
    "DLHLPPPPD",
    "DLLPPPPPD",
    "DPPPPPPPD",
    ".DPPPPPD.",
    ".DDPPPDD.",
    "...DDD...",
]


def write_png(path, rows):
    height, width = len(rows), len(rows[0])
    raw = b"".join(b"\x00" + b"".join(bytes(p) for p in row) for row in rows)

    def chunk(tag, body):
        c = tag + body
        return struct.pack(">I", len(body)) + c + struct.pack(">I", zlib.crc32(c))

    png = (b"\x89PNG\r\n\x1a\n"
           + chunk(b"IHDR", struct.pack(">IIBBBBB", width, height, 8, 6, 0, 0, 0))
           + chunk(b"IDAT", zlib.compress(raw, 9))
           + chunk(b"IEND", b""))
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "wb") as f:
        f.write(png)
    print("wrote %s (%dx%d)" % (path, width, height))


def render(art):
    size = 10
    rows = []
    for y in range(size):
        row = []
        for x in range(size):
            here = art[y][x] if y < 9 and x < 9 else "."
            above_left = art[y - 1][x - 1] if 0 < y <= 9 and 0 < x <= 9 else "."
            if here in COLOURS:
                row.append(COLOURS[here])
            elif above_left in COLOURS:
                row.append(SHADOW)
            else:
                row.append(CLEAR)
        rows.append(row)
    return rows


def main():
    assert len(PEARL) == 9 and all(len(r) == 9 for r in PEARL)
    write_png(os.path.join(OUT, "icon_pearl.png"), render(PEARL))


if __name__ == "__main__":
    main()
