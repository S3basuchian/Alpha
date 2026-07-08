#!/usr/bin/env python3
"""Drive clingo via its multi-shot Python API on the reach benchmark.

Pattern: declare `#external edge(X, Y) : node(X), node(Y)` over the node
universe seen in the instance file, ground the reachable/2 extension over
that universe once, then flip per shot.

The recursive `reachable(X, Y) :- reachable(X, Z), edge(Z, Y).` rule
grounds over (X, Y, Z) where each variable ranges over the node universe,
so the upfront cost is O(V³) ground-rule instances. For the reach-1
instance (~12 000 nodes) this is impractical (~10¹² candidate rules);
even on a 500-edge subset the per-shot solve might fit but the base
setup is dominated by gringo's full materialisation of the recursion.

This script is included to *demonstrate* the multi-shot failure mode
on V² external universes — directly analogous to the cutedge experiment.

Usage:
  python3 clingo-multishot.py <encoding.lp> <edges.lp> <numShots>
"""

import re
import sys
import time

import clingo

EDGE_RE = re.compile(r"edge\((\d+)\s*,\s*(\d+)\)\.")


def parse_edges(path):
    out = []
    nodes = set()
    with open(path) as f:
        for line in f:
            m = EDGE_RE.search(line)
            if m:
                a, b = int(m.group(1)), int(m.group(2))
                out.append((a, b))
                nodes.add(a); nodes.add(b)
    return out, nodes


def split_into_chunks(items, num_chunks):
    n = len(items)
    base = n // num_chunks
    rem = n % num_chunks
    out = []
    off = 0
    for i in range(num_chunks):
        size = base + (1 if i < rem else 0)
        out.append(items[off:off + size])
        off += size
    return out


def main():
    if len(sys.argv) < 4:
        print("Usage: clingo-multishot.py <encoding.lp> <edges.lp> <numShots> [viabilityCap]", file=sys.stderr)
        sys.exit(2)
    encoding_path, edges_path, n_str = sys.argv[1], sys.argv[2], sys.argv[3]
    cap = int(sys.argv[4]) if len(sys.argv) >= 5 else 4_000_000
    num_shots = int(n_str)

    with open(encoding_path) as f:
        encoding = f.read()
    edges, nodes = parse_edges(edges_path)
    chunks = split_into_chunks(edges, num_shots)

    # `#external edge(X,Y):node(X),node(Y)` grounds the recursion over O(V^2)
    # node pairs. Above the viability cap that base grounding is impractical
    # (memout / minutes), so bail out cleanly rather than hanging the machine.
    nv = len(nodes)
    if nv * nv > cap:
        print(f"  base setup FAIL: {nv} nodes -> {nv * nv:,} edge externals "
              f"exceeds viability cap {cap:,}")
        print(f"\n  clingo MSS not viable: streaming reachability over a {nv}-node universe "
              f"needs {nv * nv:,} ground edge atoms (the cutedge/groundexp grounding wall).")
        sys.exit(3)

    # Declare only the actually-occurring nodes, not the full 0..max range.
    node_facts = "\n".join(f"node({n})." for n in sorted(nodes))
    base_program = (
        node_facts + "\n"
        + encoding
        + "\n#external edge(X, Y) : node(X), node(Y).\n"
    )

    ctl = clingo.Control(["1"])
    t_setup_start = time.time()
    ctl.add("base", [], base_program)
    ctl.ground([("base", [])])
    setup_time = time.time() - t_setup_start

    print(f"  base setup ({nv} nodes, {nv*nv} edge externals): {setup_time:.3f}s")
    print()
    print(f"{'shot':<6} | {'edges +':<10} | {'edges total':<12} | {'clingo MSS (s)':<14}")
    print(f"{'-'*6}-+-{'-'*10}-+-{'-'*12}-+-{'-'*14}")

    cumulative = 0
    total = 0.0
    for shot in range(1, num_shots + 1):
        chunk = chunks[shot - 1]
        cumulative += len(chunk)
        t0 = time.time()
        for a, b in chunk:
            ctl.assign_external(clingo.Function("edge", [clingo.Number(a), clingo.Number(b)]), True)
        found = []
        ctl.solve(on_model=lambda m: found.append(True) or False)
        elapsed = time.time() - t0
        total += elapsed
        suffix = "" if found else "  (UNSAT)"
        print(f"{shot:<6d} | {len(chunk):<10d} | {cumulative:<12d} | {elapsed:14.3f}{suffix}")

    print()
    print(f"  total clingo MSS solve time: {total:.3f}s over {num_shots} shots")
    print(f"  total clingo MSS wall time (incl. base setup): {setup_time + total:.3f}s")


if __name__ == "__main__":
    main()
