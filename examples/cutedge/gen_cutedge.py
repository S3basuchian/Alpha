#!/usr/bin/env python3
"""Generate a cutedge instance (Leutgeb & Weinzierl 2018 benchmark).

Port of the original Alpha-team generator
(alpha-asp/benchmarks: generators/CutEdge.java): a random directed graph over
N vertices where each ordered pair (i, j) becomes an edge with probability
`percentEdges`%.

Repo conventions (so instances drop into the existing benchmark + shared
examples/cutedge/encoding.lp unchanged):
  * 0-indexed vertices 0..N-1 (matches the shipped edges-100-30 / -50 instances)
  * fixed reachability target node 98 (matches encoding.lp). The original
    generator picks the target at random in 1..N; for an Erdos-Renyi-style
    random digraph every vertex is statistically equivalent, so fixing it is
    methodologically neutral and only requires N >= 99.
  * instance file = the target-specific recursive rule on line 1, then edges.

Usage: gen_cutedge.py <numVertices> <percentEdges> [seed]
Writes instances/edges-<N>-<pct>.lp
"""
import os
import random
import sys

TARGET = 98  # must equal the target in encoding.lp; requires N >= 99


def main():
    if len(sys.argv) < 3:
        print("Usage: gen_cutedge.py <numVertices> <percentEdges> [seed]", file=sys.stderr)
        sys.exit(2)
    n = int(sys.argv[1])
    pct = int(sys.argv[2])
    seed = int(sys.argv[3]) if len(sys.argv) > 3 else 42
    if n <= TARGET:
        print(f"numVertices must be > {TARGET} (target node) -- got {n}", file=sys.stderr)
        sys.exit(2)
    rng = random.Random(seed)

    here = os.path.dirname(os.path.abspath(__file__))
    os.makedirs(os.path.join(here, "instances"), exist_ok=True)
    out = os.path.join(here, "instances", f"edges-{n}-{pct}.lp")

    lines = [f"reachable(X,{TARGET}) :- reachable(X,Z),reachable(Z,{TARGET}).\n"]
    for i in range(n):
        for j in range(n):
            if rng.randint(1, 100) <= pct:
                lines.append(f"edge({i},{j}).\n")
    with open(out, "w") as f:
        f.writelines(lines)
    print(f"wrote {out}: {len(lines) - 1} edges, {n} nodes, target {TARGET}, density {pct}% (seed {seed})")


if __name__ == "__main__":
    main()
