#!/usr/bin/env python3
"""clingo multi-shot (MSS) driver for the incremental 5-colouring benchmark — the "clingo MSS"
column of the paper's Table 4.

The Alpha colouring driver (IncrementalColoringBenchmark, run with -DclingoDumpDir=<dir>) records
the model-dependent edit stream by dumping the FULL program of every shot as shot-NNN.lp. This
driver reconstructs the per-shot edit by diffing consecutive dumps and replays it against ONE
long-lived clingo Control, so grounding is incremental (each vertex / edge / forbid grounded once):

    vertices  grow-only  -> ground a `vertex(v)` program instance (its 5 colour-choice rules)
    edges     toggleable -> `#external e(a,b)`; ground the 5 edge-colour constraints once, then
                            assign the external true (added) / false (retracted)
    forbids   add-only   -> ground the ground constraint `:- cK(v).`

Every shot solves for the first answer set (solve-first, like clingo-coloring.py rebuilt), so the
comparison is apples-to-apples with the other three columns. Prints timing lines whose labels match
the other MSS drivers (clingo-multishot.py / clingo-cutedge.py), so the copperbench wrapper greps
the same "total clingo MSS wall time (incl. base setup):" line.

Usage:
    python3 clingo-coloring-mss.py <dumpDir>
"""
import os
import re
import sys
import time
from glob import glob

import clingo

VERTEX_TEMPLATE = """
c1(v) :- not c2(v), not c3(v), not c4(v), not c5(v).
c2(v) :- not c1(v), not c3(v), not c4(v), not c5(v).
c3(v) :- not c1(v), not c2(v), not c4(v), not c5(v).
c4(v) :- not c1(v), not c2(v), not c3(v), not c5(v).
c5(v) :- not c1(v), not c2(v), not c3(v), not c4(v).
"""
EDGE_TEMPLATE = """
#external e(a,b).
:- e(a,b), c1(a), c1(b).
:- e(a,b), c2(a), c2(b).
:- e(a,b), c3(a), c3(b).
:- e(a,b), c4(a), c4(b).
:- e(a,b), c5(a), c5(b).
"""

RE_V = re.compile(r"^v\((\d+)\)\.")
RE_E = re.compile(r"^e\((\d+),(\d+)\)\.")
RE_FORBID = re.compile(r"^:-\s*c([1-5])\((\d+)\)\.")


def parse_shot(path):
    """Return (vertices:set[int], edges:set[(int,int)], forbids:set[(int,int)]) from one dump file.
    Encoding rules (which contain variables / 'not') are ignored — only ground facts/constraints."""
    verts, edges, forbids = set(), set(), set()
    with open(path) as fh:
        for line in fh:
            line = line.strip()
            m = RE_V.match(line)
            if m:
                verts.add(int(m.group(1))); continue
            m = RE_E.match(line)
            if m:
                edges.add((int(m.group(1)), int(m.group(2)))); continue
            m = RE_FORBID.match(line)
            if m:
                forbids.add((int(m.group(2)), int(m.group(1))))  # (vertex, colour)
    return verts, edges, forbids


def main():
    if len(sys.argv) != 2:
        print("Usage: clingo-coloring-mss.py <dumpDir>", file=sys.stderr)
        sys.exit(2)
    dump_dir = sys.argv[1]
    shots = sorted(glob(os.path.join(dump_dir, "shot-*.lp")))
    if not shots:
        print(f"no shot-*.lp in {dump_dir}", file=sys.stderr)
        sys.exit(3)

    wall0 = time.time()
    ctl = clingo.Control(["--models=1"])
    ctl.add("vertex", ["v"], VERTEX_TEMPLATE)
    ctl.add("edge", ["a", "b"], EDGE_TEMPLATE)

    grounded_v, grounded_e, forbid_n = set(), set(), 0

    def ground_vertex(v):
        ctl.ground([("vertex", [clingo.Number(v)])]); grounded_v.add(v)

    def ensure_edge(a, b, active):
        if (a, b) not in grounded_e:
            ctl.ground([("edge", [clingo.Number(a), clingo.Number(b)])]); grounded_e.add((a, b))
        ctl.assign_external(clingo.Function("e", [clingo.Number(a), clingo.Number(b)]), active)

    def add_forbid(v, c):
        nonlocal forbid_n
        forbid_n += 1
        ctl.add(f"forbid{forbid_n}", [], f":- c{c}({v}).")
        ctl.ground([(f"forbid{forbid_n}", [])])

    print(f"==== clingo MSS (one Control, incremental grounding, {len(shots)} shots) ====")
    print(f"{'shot':<6} | {'|V|':<6} | {'|E|':<7} | {'forbid':<7} | {'result':<8} | {'solve (s)':<10}")
    print(f"{'-'*6}-+-{'-'*6}-+-{'-'*7}-+-{'-'*7}-+-{'-'*8}-+-{'-'*10}")

    prev_v, prev_e, prev_f = set(), set(), set()
    setup_secs = None
    solve_total = 0.0
    for i, path in enumerate(shots):
        cur_v, cur_e, cur_f = parse_shot(path)
        for v in sorted(cur_v - prev_v):
            ground_vertex(v)
        for (a, b) in sorted(cur_e - prev_e):        # added / (re)activated edges
            ensure_edge(a, b, True)
        for (a, b) in sorted(prev_e - cur_e):        # retracted edges
            ensure_edge(a, b, False)
        for (v, c) in sorted(cur_f - prev_f):        # new forbid constraints
            add_forbid(v, c)
        if i == 0:
            setup_secs = time.time() - wall0        # grounding the base graph

        t0 = time.time()
        res = ctl.solve()
        dt = time.time() - t0
        solve_total += dt
        result = "SAT" if res.satisfiable else "UNSAT"
        print(f"{i+1:<6} | {len(cur_v):<6} | {len(cur_e):<7} | {len(cur_f):<7} | {result:<8} | {dt:<10.3f}")
        prev_v, prev_e, prev_f = cur_v, cur_e, cur_f

    wall_total = time.time() - wall0
    print(f"  base setup (grounding shot-001 graph): {setup_secs:.3f}s")
    print(f"  total clingo MSS solve time: {solve_total:.3f}s over {len(shots)} shots")
    print(f"  total clingo MSS wall time (incl. base setup): {wall_total:.3f}s")


if __name__ == "__main__":
    main()
