"""Render a SifuCraft collision voxel file (.scvx, written by sifu-native) for checking.

  python tools/collision/voxview.py <file.scvx> --png <prefix> [--radius 40]
      <prefix>_floor.png: top view of the floor the player would stand on (highest solid cell up to
      2 m above the pawn's feet), coloured by height relative to the feet; the pawn is the white
      cross. <prefix>_walls.png: what is solid at waist height (feet + 0.5 m .. + 1.5 m).
  python tools/collision/voxview.py <file.scvx> --obj <file.obj> [--radius 40]
      The exposed cell faces, merged into rectangles, in Minecraft orientation (x, y, z) =
      (UE.x, UE.z, UE.y) in metres.

Images: one pixel per cell (12.5 cm), +x to the right, +y (Minecraft +z) downwards.
"""

import argparse
import struct
import sys
import zlib
from pathlib import Path


def load(path):
    data = Path(path).read_bytes()
    if data[:4] != b"SCVX":
        sys.exit(f"{path}: not a SCVX file")
    version, cell_cm, per_block = struct.unpack_from("<IfI", data, 4)
    if version != 1:
        sys.exit(f"{path}: unsupported version {version}")
    feet = struct.unpack_from("<3f", data, 16)
    (name_length,) = struct.unpack_from("<I", data, 28)
    name = data[32:32 + name_length].decode("utf-8", "replace")
    at = 32 + name_length
    (count,) = struct.unpack_from("<I", data, at)
    at += 4
    cells = set()
    for _ in range(count):
        bx, by, bz = struct.unpack_from("<3i", data, at)
        layers = struct.unpack_from("<8Q", data, at + 12)
        at += 12 + 64
        for lz, layer in enumerate(layers):
            while layer:
                bit = (layer & -layer).bit_length() - 1
                layer &= layer - 1
                cells.add((bx * per_block + bit % per_block, by * per_block + bit // per_block, bz * per_block + lz))
    return {"cell": cell_cm, "feet": feet, "map": name, "cells": cells, "blocks": count}


def write_png(path, width, height, pixels):
    """pixels: rows of (r, g, b) tuples."""
    raw = b"".join(b"\x00" + bytes(c for px in row for c in px) for row in pixels)

    def chunk(kind, payload):
        body = kind + payload
        return struct.pack(">I", len(payload)) + body + struct.pack(">I", zlib.crc32(body) & 0xFFFFFFFF)

    png = b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", width, height, 8, 2, 0, 0, 0))
    png += chunk(b"IDAT", zlib.compress(raw, 9)) + chunk(b"IEND", b"")
    Path(path).write_bytes(png)


def height_colour(dz_cm):
    """Blue below the feet, green at feet level, yellow to red above."""
    if dz_cm < -25:
        t = min(1.0, -dz_cm / 300.0)
        return (0, int(80 + 100 * (1 - t)), int(160 + 95 * (1 - t)))
    if dz_cm <= 25:
        return (40, 200, 60)
    t = min(1.0, dz_cm / 200.0)
    return (255, int(220 * (1 - t)), 0)


def render_png(v, prefix, radius_m):
    cell = v["cell"]
    fx, fy, fz = v["feet"]
    half = int(radius_m * 100 / cell)
    cx, cy, cz = int(fx // cell), int(fy // cell), int(fz // cell)
    size = 2 * half + 1
    top_limit = int((fz + 200) // cell)
    columns = {}
    for (x, y, z) in v["cells"]:
        if abs(x - cx) > half or abs(y - cy) > half or z > top_limit:
            continue
        key = (x, y)
        if z > columns.get(key, -10**9):
            columns[key] = z
    waist = set(range(int((fz + 50) // cell), int((fz + 150) // cell) + 1))
    walls = {(x, y) for (x, y, z) in v["cells"] if z in waist and abs(x - cx) <= half and abs(y - cy) <= half}

    floor_rows, wall_rows = [], []
    for row in range(size):
        y = cy - half + row
        frow, wrow = [], []
        for col in range(size):
            x = cx - half + col
            top = columns.get((x, y))
            frow.append((0, 0, 0) if top is None else height_colour((top + 1) * cell - fz))
            wrow.append((230, 230, 230) if (x, y) in walls else (20, 20, 30))
        floor_rows.append(frow)
        wall_rows.append(wrow)
    for rows in (floor_rows, wall_rows):
        for d in range(-6, 7):
            for (px, py) in ((half + d, half), (half, half + d)):
                rows[py][px] = (255, 0, 255) if rows is wall_rows else (255, 255, 255)
    write_png(f"{prefix}_floor.png", size, size, floor_rows)
    write_png(f"{prefix}_walls.png", size, size, wall_rows)
    under = columns.get((cx, cy))
    print(f"floor under the pawn: {'none' if under is None else f'{(under + 1) * cell:.1f} cm'} "
          f"(feet {fz:.1f} cm); images {size}x{size} px")


def render_obj(v, path, radius_m):
    """Exposed faces, merged per face direction and slice into maximal rectangles."""
    cell = v["cell"]
    fx, fy, fz = v["feet"]
    half = int(radius_m * 100 / cell)
    cx, cy = int(fx // cell), int(fy // cell)
    cells = {c for c in v["cells"] if abs(c[0] - cx) <= half and abs(c[1] - cy) <= half}
    out = ["# SifuCraft collision voxels: Minecraft orientation, metres"]
    vertices = 0
    faces = 0
    for axis in range(3):
        u_axis, w_axis = (axis + 1) % 3, (axis + 2) % 3
        for sign in (1, -1):
            slices = {}
            for c in cells:
                n = list(c)
                n[axis] += sign
                if tuple(n) in cells:
                    continue
                slices.setdefault(c[axis], set()).add((c[u_axis], c[w_axis]))
            for level, mask in slices.items():
                while mask:
                    u0, w0 = min(mask)
                    u1 = u0
                    while (u1 + 1, w0) in mask:
                        u1 += 1
                    w1 = w0
                    while all((u, w1 + 1) in mask for u in range(u0, u1 + 1)):
                        w1 += 1
                    for u in range(u0, u1 + 1):
                        for w in range(w0, w1 + 1):
                            mask.discard((u, w))
                    plane = level + (1 if sign > 0 else 0)
                    corners = [(u0, w0), (u1 + 1, w0), (u1 + 1, w1 + 1), (u0, w1 + 1)]
                    if sign < 0:
                        corners.reverse()
                    for (u, w) in corners:
                        p = [0, 0, 0]
                        p[axis], p[u_axis], p[w_axis] = plane, u, w
                        ue = [p[k] * cell / 100.0 for k in range(3)]
                        out.append(f"v {ue[0]:.3f} {ue[2]:.3f} {ue[1]:.3f}")
                    # The axis swap mirrors the mesh: reverse the winding to keep faces outward.
                    out.append(f"f {vertices + 4} {vertices + 3} {vertices + 2} {vertices + 1}")
                    vertices += 4
                    faces += 1
    Path(path).write_text("\n".join(out) + "\n")
    print(f"{faces} faces from {len(cells)} cells -> {path}")


def main():
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("file")
    parser.add_argument("--png", help="output prefix for the top-view images")
    parser.add_argument("--obj", help="output OBJ of the exposed faces")
    parser.add_argument("--radius", type=float, default=40.0, help="metres around the pawn (default 40)")
    parser.add_argument("--center", type=float, nargs=3, metavar=("X", "Y", "FEET_Z"),
                        help="look around this Unreal position (cm) instead of the pawn's")
    args = parser.parse_args()
    v = load(args.file)
    if args.center:
        v["feet"] = tuple(args.center)
    print(f"{v['map']}: {v['blocks']} blocks, {len(v['cells'])} cells of {v['cell']} cm, pawn feet "
          f"({v['feet'][0]:.0f}, {v['feet'][1]:.0f}, {v['feet'][2]:.1f})")
    if args.png:
        render_png(v, args.png, args.radius)
    if args.obj:
        render_obj(v, args.obj, args.radius)


if __name__ == "__main__":
    main()
