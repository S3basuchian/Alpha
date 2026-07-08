#!/usr/bin/env python3
"""Generate random directed graphs matching the reachability benchmark of

    Leutgeb & Weinzierl (2018), "Techniques for Efficient Lazy-Grounding ASP
    Solving", Sect. 5, Table 5.

The paper's setup: random graphs with 1.000 and 10.000 vertices and 4.000 to
80.000 edges ("edges between two randomly selected nodes, i.e., no further
structure"), 10 randomly generated instances per size. Instance size is written
as vertices/edge-multiple, so 1000/4 == 1000 vertices, 4000 edges.

Emitted as `edge(u,v).` facts, one per line, in generation (i.e. random) order
so the incremental driver streams them in a natural random arrival order. The
start vertex used by encoding.lp is fixed at 1; to avoid a degenerate
"reaches nothing" run, the first emitted edge always leaves vertex 1.

Edges are distinct directed pairs (no self-loops, no duplicates). Deterministic
given --seed, so instances are regenerable.

Usage:
  # single instance
  python3 gen-random-graph.py --vertices 1000 --edges 8000 --seed 0 \
      > instances/edges-rand-v1000-e8000.lp

  # regenerate the whole paper grid into instances/ (10 seeds optional)
  python3 gen-random-graph.py --grid
"""

import argparse
import os
import random
import sys

# The paper's grid: (vertices, edge-multiple) -> edges = vertices * multiple.
GRID = [
    (1000, 4),    # 1000/4  ->  4000 edges
    (1000, 8),    # 1000/8  ->  8000 edges
    (10000, 2),   # 10000/2 -> 20000 edges
    (10000, 4),   # 10000/4 -> 40000 edges
    (10000, 8),   # 10000/8 -> 80000 edges
]


def generate(vertices, edges, seed, start=1):
    """Return a list of `edges` distinct directed (u, v) pairs over 1..vertices.

    No self-loops, no duplicates. The first pair always leaves `start` so the
    reachable set is non-trivial. Sampling order is preserved (random arrival).
    """
    rng = random.Random(seed)
    max_edges = vertices * (vertices - 1)
    if edges > max_edges:
        raise ValueError(f"{edges} edges exceeds max {max_edges} for {vertices} vertices")

    seen = set()
    out = []

    # Guarantee the start vertex has at least one out-edge.
    v0 = rng.randint(1, vertices)
    while v0 == start:
        v0 = rng.randint(1, vertices)
    seen.add((start, v0))
    out.append((start, v0))

    while len(out) < edges:
        u = rng.randint(1, vertices)
        v = rng.randint(1, vertices)
        if u == v:
            continue
        if (u, v) in seen:
            continue
        seen.add((u, v))
        out.append((u, v))
    return out


def write_instance(path, vertices, edges, seed):
    pairs = generate(vertices, edges, seed)
    with open(path, "w") as f:
        f.write(f"% random directed graph: {vertices} vertices, {edges} edges, "
                f"seed={seed} (Leutgeb & Weinzierl 2018, Table 5 grid)\n")
        for (u, v) in pairs:
            f.write(f"edge({u},{v}).\n")
    return len(pairs)


def main():
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--vertices", type=int)
    ap.add_argument("--edges", type=int)
    ap.add_argument("--seed", type=int, default=0)
    ap.add_argument("--grid", action="store_true",
                    help="generate the whole paper grid into ./instances/")
    ap.add_argument("--instances", type=int, default=1,
                    help="with --grid: how many seeds per size (default 1)")
    args = ap.parse_args()

    if args.grid:
        here = os.path.dirname(os.path.abspath(__file__))
        outdir = os.path.join(here, "instances")
        os.makedirs(outdir, exist_ok=True)
        for (v, mult) in GRID:
            e = v * mult
            for s in range(args.instances):
                suffix = "" if args.instances == 1 else f"-s{s}"
                name = f"edges-rand-v{v}-e{e}{suffix}.lp"
                path = os.path.join(outdir, name)
                n = write_instance(path, v, e, seed=s)
                print(f"wrote {name}: {n} edges", file=sys.stderr)
        return

    if args.vertices is None or args.edges is None:
        ap.error("provide --vertices and --edges, or --grid")
    pairs = generate(args.vertices, args.edges, args.seed)
    sys.stdout.write(f"% random directed graph: {args.vertices} vertices, "
                     f"{args.edges} edges, seed={args.seed}\n")
    for (u, v) in pairs:
        sys.stdout.write(f"edge({u},{v}).\n")


if __name__ == "__main__":
    main()
