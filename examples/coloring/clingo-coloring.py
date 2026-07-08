#!/usr/bin/env python3
"""clingo comparison for the coloring benchmark, "rebuilt each shot" mode.

The mixed edit stream (grow / add-edge / retract / constrain) is model- and
RNG-dependent, so — exactly like the Java driver's batch replay — clingo cannot
regenerate it independently. Instead the Java driver is run with
  -DclingoDumpDir=<dir>
which writes the full self-contained program of every replayed shot to
<dir>/shot-001.lp, shot-002.lp, ...  This driver solves each of those dumps from
scratch with a fresh clingo.Control (grounding + solving the identical state the
Java `batch` mode saw) and reports the summed wall time for the first answer set
per shot — the faithful "clingo rebuilt" column.

(clingo multi-shot / MSS is intentionally not provided here: representing edge
*retraction* as #external flips over a single long-lived Control does not map
cleanly onto this rotating edit stream, and clingo's per-shot cost on these
grounding-free instances is dominated by base setup anyway. Use the rebuilt
column for the honest head-to-head.)

Usage:  ./clingo-coloring.py <dumpDir>
"""
import glob
import os
import sys
import time

import clingo


def solve_first(program_text):
    ctl = clingo.Control(["--models=1"])
    ctl.add("base", [], program_text)
    ctl.ground([("base", [])])
    sat = [False]

    def on_model(_m):
        sat[0] = True

    ctl.solve(on_model=on_model)
    return sat[0]


def main():
    if len(sys.argv) != 2:
        sys.exit("usage: clingo-coloring.py <dumpDir>")
    dump_dir = sys.argv[1]
    shots = sorted(glob.glob(os.path.join(dump_dir, "shot-*.lp")))
    if not shots:
        sys.exit(f"no shot-*.lp files in {dump_dir}")

    total = 0.0
    for path in shots:
        with open(path) as fh:
            prog = fh.read()
        t0 = time.time()
        sat = solve_first(prog)
        dt = time.time() - t0
        total += dt
        print(f"  {os.path.basename(path)}: {dt:.3f}s  ({'SAT' if sat else 'UNSAT'})")

    print(f"total clingo rebuilt time: {total:.3f}s over {len(shots)} shots")


if __name__ == "__main__":
    main()
