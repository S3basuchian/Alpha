#!/usr/bin/env python3
"""clingo model-dependent forbid-all driver for the ground-explosion benchmark (own sequence).

Each shot enumerates up to maxAS answer sets, collects every element selected in them
(the `sel(i)` atoms), and blocks ALL of them via `:- p(i,...,i).`, then re-solves — the same
"solve, block the solutions you saw, re-solve" loop the Alpha driver runs, but driving clingo's
OWN sequence from clingo's OWN models (no shared/replayed stream — cf. the cutedge methodology).

Two modes:
  rebuilt : a fresh clingo.Control each shot (re-grounds encoding + dom + all blocks so far).
  mss     : one long-lived Control; the p/6 base is grounded once, blocks grounded incrementally.

Both eagerly ground the p/6 cross-product, so for |dom| >= 18 the base grounding is the L&W 2018
grounding wall — clingo mem/times-out here (runsolver marks the cell), exactly as in the paper.

Prints a total line whose label matches the other drivers so the copperbench wrapper greps it:
  rebuilt -> "total clingo rebuilt time: <t>s over <n> shots"
  mss     -> "total clingo MSS wall time (incl. base setup): <t>s"

Usage:  clingo-model-forbid.py <encoding.lp> <dom.lp> <numShots> <maxAS> <rebuilt|mss>
"""
import sys
import time

import clingo


def constraint(e):
    return f":- p({e},{e},{e},{e},{e},{e}).\n"


def enumerate_upto(ctl, max_as):
    """Solve, returning the list of selected elements (sel/1 args) across up to max_as models."""
    found = []
    seen = set()

    def on_model(m):
        for s in m.symbols(atoms=True):
            if s.name == "sel" and len(s.arguments) == 1:
                e = str(s.arguments[0])
                if e not in seen:
                    seen.add(e)
                    found.append(e)

    ctl.configuration.solve.models = max_as
    ctl.solve(on_model=on_model)
    return found


def main():
    encoding = open(sys.argv[1]).read()
    dom = open(sys.argv[2]).read()
    shots = int(sys.argv[3])
    max_as = int(sys.argv[4])
    mode = sys.argv[5]

    if mode == "rebuilt":
        cons = ""
        total = 0.0
        for _shot in range(shots):
            ctl = clingo.Control()
            ctl.add("base", [], encoding + dom + cons)
            t0 = time.time()
            ctl.ground([("base", [])])
            found = enumerate_upto(ctl, max_as)
            total += time.time() - t0
            if not found:
                break
            for e in found:
                cons += constraint(e)
        print(f"total clingo rebuilt time: {total:.3f}s over {shots} shots")

    elif mode == "mss":
        ctl = clingo.Control()
        ctl.add("base", [], encoding + dom)
        t0 = time.time()
        ctl.ground([("base", [])])
        setup = time.time() - t0
        solve_total = 0.0
        for shot in range(shots):
            t0 = time.time()
            found = enumerate_upto(ctl, max_as)
            solve_total += time.time() - t0
            if not found:
                break
            prog = f"blk{shot}"
            ctl.add(prog, [], "".join(constraint(e) for e in found))
            t1 = time.time()
            ctl.ground([(prog, [])])
            solve_total += time.time() - t1
        print(f"  base setup (grounding p/6 cross-product): {setup:.3f}s")
        print(f"  total clingo MSS solve time: {solve_total:.3f}s over {shots} shots")
        print(f"  total clingo MSS wall time (incl. base setup): {setup + solve_total:.3f}s")

    else:
        print(f"unknown mode: {mode} (use rebuilt|mss)", file=sys.stderr)
        sys.exit(2)


if __name__ == "__main__":
    main()
