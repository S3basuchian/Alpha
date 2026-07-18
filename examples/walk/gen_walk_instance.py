#!/usr/bin/env python3
"""Instance generator for the Gardener's Walk paper table.

Emits a spec file shared verbatim by the Java (Alpha) and Python (clingo) drivers:
    gard C R
    frog I C R
    wall C R
Walls are random cells; the generator guarantees the FREE space is connected (BFS from
the gardener start reaches every free cell), so every position stays accessible for
gardener and frogs. Frogs are placed on random free cells at Manhattan distance
>= dmin from the gardener start.

Usage: gen_walk_instance.py W numFrogs seed wallPct dmin [openR=3]
"""
import os
import random
import sys
from collections import deque


def connected(free, w, start):
    seen = {start}
    q = deque([start])
    while q:
        c, r = q.popleft()
        for dc, dr in ((0, 1), (0, -1), (1, 0), (-1, 0)):
            n = (c + dc, r + dr)
            if n in free and n not in seen:
                seen.add(n)
                q.append(n)
    return len(seen) == len(free)


def main():
    w, nf, seed, wallpct, dmin = int(sys.argv[1]), int(sys.argv[2]), int(sys.argv[3]), float(sys.argv[4]), int(sys.argv[5])
    rng = random.Random(seed)
    gard = (w // 2, w // 2)
    cells = [(c, r) for c in range(1, w + 1) for r in range(1, w + 1)]
    nwalls = int(len(cells) * wallpct / 100.0)
    # keep a wall-free open disk of radius `openr` around the gardener start so it can never be
    # boxed into a pocket (the source of lost-game UNSAT states). openr=0 disables (legacy behaviour).
    openr = int(sys.argv[6]) if len(sys.argv) > 6 else 3
    protected = {(c, r) for c in range(gard[0] - openr, gard[0] + openr + 1)
                 for r in range(gard[1] - openr, gard[1] + openr + 1)
                 if 1 <= c <= w and 1 <= r <= w and abs(c - gard[0]) + abs(r - gard[1]) <= openr}
    placeable = [x for x in cells if x not in protected]
    for _attempt in range(200):
        walls = set(rng.sample(placeable, min(nwalls, len(placeable))))
        free = set(cells) - walls
        if connected(free, w, gard):
            break
    else:
        sys.exit("could not generate a connected instance")
    candidates = [x for x in free if abs(x[0] - gard[0]) + abs(x[1] - gard[1]) >= dmin]
    frogs = rng.sample(candidates, nf)
    print(f"# walk instance W={w} f={nf} seed={seed} wallpct={wallpct} dmin={dmin}")
    print(f"gard {gard[0]} {gard[1]}")
    for i, (c, r) in enumerate(frogs, 1):
        print(f"frog {i} {c} {r}")
    for (c, r) in sorted(walls):
        print(f"wall {c} {r}")


if __name__ == "__main__":
    main()
