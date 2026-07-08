#!/usr/bin/env python3
"""Drive clingo multi-shot on the *constraint-streaming* Ground Explosion variant.

This is the clingo multi-shot counterpart to the Alpha `live` session in
`IncrementalGroundExplosionConstraintBenchmark`. The universe `dom/1` is fixed
and complete, grounded **once** at base time; each subsequent shot adds the next
`forbidPerShot` forbidding constraints `:- p(i,...,i).` as a fresh subprogram,
grounds only that subprogram, and re-solves for up to `maxModels` answer sets.

**Expected to fail / be very slow for a fixed |dom| >= 18.** Because the whole
universe is declared up front, gringo grounds the `p/6` rule over the full
|dom|^6 candidate space at base time — before a single constraint is streamed.
This is the same upfront materialisation that defeats multi-shot clingo whenever
a `dom/1` universe is declared eagerly (the L&W 2018 grounding wall), except here
it is paid entirely at setup. Streaming *constraints* on top of an already-ground
universe is cheap; it is the universe itself that explodes.

Counting note: like the Alpha driver, each shot enumerates up to `maxModels`
answer sets (default 10), a fixed bounded per-shot target. The printed count is
`min(available, maxModels)`, so it stays pinned at the cap while more than
`maxModels` selections survive and then drops as the constraints exhaust them —
it must match the Alpha live/batch count columns shot-for-shot.

Usage:
  clingo-multishot-constraints.py <encoding.lp> <dom.lp> <numShots> [forbidPerShot] [maxModels] [forbidTotal] [forbidOrderFile]

forbidTotal (default |dom|) caps the number of distinct elements ever forbidden across the run, so a
caller can leave the tail of the domain unconstrained (e.g. forbidTotal = round(0.9*|dom|) leaves ~10%).
It must match the cap used by the Alpha driver for the per-shot answer-set counts to agree.

forbidOrderFile (one element per line) supplies the exact forbidding sequence; without it, elements are
forbidden in dom-file order. The harness passes the same file to every solver so they forbid the
identical set even under a permuted (desc/scatter) order.
"""

import re
import sys
import time

import clingo

DOM_RE = re.compile(r"dom\((\d+)\)\.")


def parse_dom(path):
    out = []
    with open(path) as f:
        for line in f:
            m = DOM_RE.search(line)
            if m:
                out.append(int(m.group(1)))
    return out


def main():
    if len(sys.argv) < 4:
        print("Usage: clingo-multishot-constraints.py <encoding.lp> <dom.lp> <numShots> [forbidPerShot] [maxModels] [forbidTotal]", file=sys.stderr)
        sys.exit(2)
    encoding_path, dom_path, n_str = sys.argv[1], sys.argv[2], sys.argv[3]
    num_shots = int(n_str)
    # forbidPerShot is a single integer (uniform) or a comma-separated per-shot schedule "n1,n2,n3"
    # (constraint shot j forbids n_j; front-loaded/decayed). Must match the Alpha driver.
    forbid_arg = sys.argv[4] if len(sys.argv) >= 5 else "1"
    if "," in forbid_arg:
        forbid_schedule = [int(x) for x in forbid_arg.split(",")]
        forbid_per_shot = None
    else:
        forbid_schedule = None
        forbid_per_shot = int(forbid_arg)
    max_models = int(sys.argv[5]) if len(sys.argv) >= 6 else 10

    with open(encoding_path) as f:
        encoding = f.read()
    dom_vals = parse_dom(dom_path)
    # Cap on total distinct elements forbidden across the run (default: all of them). Capping below
    # |dom| leaves the tail of the domain unconstrained; must match the Alpha driver's forbidTotal.
    forbid_total = int(sys.argv[6]) if len(sys.argv) >= 7 else len(dom_vals)

    # Forbidding sequence: an explicit order file (one element per line) if supplied, else dom-file
    # order. The universe grounded at base is always the full dom/1; only *which* elements get a
    # streamed p/6 constraint (and in what order) is governed here.
    if len(sys.argv) >= 8 and sys.argv[7]:
        with open(sys.argv[7]) as f:
            forbid_seq = [line.strip() for line in f if line.strip()]
    else:
        forbid_seq = [str(x) for x in dom_vals]
    forbid_cap = min(forbid_total, len(forbid_seq))
    nmax = max(dom_vals) if dom_vals else 0
    dom_facts = "".join(f"dom({x}).\n" for x in dom_vals)

    # Fixed universe: grounded once at base. The p/6 rule grounds over the full
    # |dom|^6 candidate space here, before any constraint shot.
    ctl = clingo.Control([str(max_models)])
    t_setup_start = time.time()
    ctl.add("base", [], dom_facts + encoding)
    ctl.ground([("base", [])])
    setup_time = time.time() - t_setup_start

    print(f"  base setup (fixed universe |dom|={nmax}, p/6 grounds ~{nmax**6:,} candidates): {setup_time:.3f}s")
    print()
    print(f"{'shot':<6} | {'constr +':<9} | {'constr total':<12} | {'answer sets':<12} | {'clingo MSS (s)':<14}")
    print(f"{'-'*6}-+-{'-'*9}-+-{'-'*12}-+-{'-'*12}-+-{'-'*14}")

    cumulative = 0
    next_el = 0
    total = 0.0
    for shot in range(1, num_shots + 1):
        # Shot 1 is the unconstrained base; shots 2.. each forbid the next
        # forbidPerShot elements via a fresh subprogram grounded this shot.
        added = 0
        step_name = None
        if shot > 1:
            if forbid_schedule is not None:
                quota = forbid_schedule[shot - 2] if shot - 2 < len(forbid_schedule) else 0
            else:
                quota = forbid_per_shot
            parts = []
            while added < quota and next_el < forbid_cap:
                e = forbid_seq[next_el]
                next_el += 1
                parts.append(f":- p({e},{e},{e},{e},{e},{e}).")
                added += 1
            if parts:
                step_name = f"step{shot}"
                ctl.add(step_name, [], "\n".join(parts) + "\n")
        cumulative += added

        t0 = time.time()
        if step_name is not None:
            ctl.ground([(step_name, [])])
        counter = [0]
        ctl.solve(on_model=lambda m: counter.__setitem__(0, counter[0] + 1))
        elapsed = time.time() - t0
        total += elapsed
        suffix = "  (UNSAT)" if counter[0] == 0 else ""
        print(f"{shot:<6d} | {added:<9d} | {cumulative:<12d} | {counter[0]:<12d} | {elapsed:14.3f}{suffix}")

    print()
    print(f"  total clingo MSS solve time: {total:.3f}s over {num_shots} shots")
    print(f"  total clingo MSS wall time (incl. base setup): {setup_time + total:.3f}s")


if __name__ == "__main__":
    main()
