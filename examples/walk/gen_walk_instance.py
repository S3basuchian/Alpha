#!/usr/bin/env python3
"""Instance generator for the Gardener's Walk paper table.

Emits a spec file shared verbatim by the Java (Alpha) and Python (clingo) drivers:
    gard C R
    frog I C R
    wall C R
Walls are random cells; the generator guarantees the FREE space is connected: after
sampling, every free cell not reachable from the gardener start (BFS) is filled in as
a wall. (Rejection sampling is hopeless here: at 10% wall density the probability of
a fully connected random sample vanishes with W — pinched-off single-cell pockets
alone appear at a rate of ~W^2/10^4.) Frogs are placed on random free cells at
Manhattan distance >= dmin from the gardener start. With nearDist > 0, frog 1 is instead
placed at Manhattan distance ~nearDist (nearest non-empty ring), so exactly one frog
engages the danger machinery from shot 1 while the rest stay background load.

Usage: gen_walk_instance.py W numFrogs seed wallPct dmin [openR=3] [nearDist=0]
"""
import os
import random
import sys
from collections import deque


def reachable(free, start):
    seen = {start}
    q = deque([start])
    while q:
        c, r = q.popleft()
        for dc, dr in ((0, 1), (0, -1), (1, 0), (-1, 0)):
            n = (c + dc, r + dr)
            if n in free and n not in seen:
                seen.add(n)
                q.append(n)
    return seen


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
    walls = set(rng.sample(placeable, min(nwalls, len(placeable))))
    free = reachable(set(cells) - walls, gard)
    pockets = len(cells) - len(walls) - len(free)
    walls = set(cells) - free
    candidates = [x for x in free if abs(x[0] - gard[0]) + abs(x[1] - gard[1]) >= dmin]
    frogs = rng.sample(candidates, nf)
    near = int(sys.argv[7]) if len(sys.argv) > 7 else 0
    if near:
        for d in range(near, near + w):
            ring = [x for x in free if abs(x[0] - gard[0]) + abs(x[1] - gard[1]) == d and x not in frogs[1:]]
            if ring:
                frogs[0] = rng.choice(ring)
                break
    print(f"# walk instance W={w} f={nf} seed={seed} wallpct={wallpct} dmin={dmin} near={near} pocketsFilled={pockets}")
    print(f"gard {gard[0]} {gard[1]}")
    for i, (c, r) in enumerate(frogs, 1):
        print(f"frog {i} {c} {r}")
    for (c, r) in sorted(walls):
        print(f"wall {c} {r}")


if __name__ == "__main__":
    main()
