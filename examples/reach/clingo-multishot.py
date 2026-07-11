#!/usr/bin/env python3
"""Drive clingo via its multi-shot Python API on the reach benchmark — the fair,
apples-to-apples counterpart to Alpha's incremental session.

Approach: **incremental `add` + `ground`, no externals at all.** One long-lived
`clingo.Control` is kept across shots. Each shot appends the newly-arrived
edge(s) as facts *together with* the recursive reachability rule in a fresh
subprogram, so gringo re-instantiates `reachable/1` over the current edge set
and the solver reuses its state (learned nogoods, heuristics) across shots.

Why no externals: `#external edge(X,Y) : node(X), node(Y)` would reserve the
full O(V^2) edge universe up front (10^8 ground atoms on a 10000-node graph),
which memouts — an artefact of the encoding, not of clingo. Externals are a
*retraction* tool; the reach stream only ever *adds* edges, so the honest
multi-shot idiom is to ground each new edge's contribution incrementally, with
no knowledge of future edges — exactly the setting Alpha's session runs in.

The recursive rule is re-supplied each shot because clingo does not re-fire an
earlier subprogram's rules against facts added in a later step; re-grounding it
lets the transitive-closure cascade reach through both old and new edges. This
is the genuine cost of unknown-future-edge incremental solving in clingo.

Usage:
  python3 clingo-multishot.py [--one-edge] [--edges-per-shot K] <encoding.lp> <edges.lp> <numShots> [ignoredCap]

  --one-edge : near-full base solved in shot 1, then edges added per shot (matches
               Alpha's -Dreach.oneEdgeShots protocol; total shots = N+1). The base is
               all but the last numShots*K edges; each shot then adds K edges.
  --edges-per-shot K : how many edges each post-base shot adds (default 1). Only meaningful
               with --one-edge; lets us vary the per-shot increment at a fixed shot count.
  Without --one-edge: the edge list is split into <numShots> roughly-equal chunks
               streamed from an empty graph.
  A trailing numeric argument (a legacy viability cap) is accepted and ignored.
"""

import re
import sys
import time

import clingo

EDGE_RE = re.compile(r"edge\((\d+)\s*,\s*(\d+)\)\.")


def parse_edges(path):
    out = []
    with open(path) as f:
        for line in f:
            m = EDGE_RE.search(line)
            if m:
                out.append((int(m.group(1)), int(m.group(2))))
    return out


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


def split_base_plus_chunks(items, num_shots, edges_per_shot):
    """Full base + K edges per shot: chunk 0 is all but the last num_shots*K edges (the near-full
    base solved in shot 1), then K edges per subsequent shot. Total shots = num_shots+1.
    edges_per_shot=1 reproduces the original one-edge-per-shot protocol exactly."""
    k = max(1, edges_per_shot)
    n = len(items)
    base_count = max(0, n - num_shots * k)
    out = [items[:base_count]]
    for i in range(base_count, n, k):
        out.append(items[i:i + k])
    return out


def main():
    argv = sys.argv[1:]
    one_edge = False
    if "--one-edge" in argv:
        one_edge = True
        argv.remove("--one-edge")
    edges_per_shot = 1
    if "--edges-per-shot" in argv:
        idx = argv.index("--edges-per-shot")
        edges_per_shot = int(argv[idx + 1])
        del argv[idx:idx + 2]
    if len(argv) < 3:
        print("Usage: clingo-multishot.py [--one-edge] [--edges-per-shot K] <encoding.lp> <edges.lp> <numShots> [ignoredCap]",
              file=sys.stderr)
        sys.exit(2)
    encoding_path, edges_path, n_str = argv[0], argv[1], argv[2]
    # argv[3], if present, is a legacy viability cap kept for CLI compatibility; ignored.
    num_shots = int(n_str)

    with open(encoding_path) as f:
        encoding = f.read()
    edges = parse_edges(edges_path)
    nodes = {x for e in edges for x in e}
    # --one-edge: near-full base in shot 1, then edges_per_shot edges per shot (matches Alpha's oneEdgeShots).
    chunks = (split_base_plus_chunks(edges, num_shots, edges_per_shot) if one_edge
              else split_into_chunks(edges, num_shots))

    # One long-lived Control, no externals. Each shot's subprogram carries the newly-arrived
    # edges *and* the encoding (so the recursive rule re-instantiates over the current edge set).
    ctl = clingo.Control(["1"])

    print(f"  incremental add+ground ({len(nodes)} nodes, {len(edges)} edges, no externals)")
    print()
    print(f"{'shot':<6} | {'edges +':<10} | {'edges total':<12} | {'clingo MSS (s)':<14}")
    print(f"{'-'*6}-+-{'-'*10}-+-{'-'*12}-+-{'-'*14}")

    cumulative = 0
    setup_time = 0.0
    solve_total = 0.0
    for shot, chunk in enumerate(chunks, start=1):
        cumulative += len(chunk)
        prog = "".join(f"edge({a},{b}). " for (a, b) in chunk) + encoding
        t0 = time.time()
        ctl.add(f"s{shot}", [], prog)
        ctl.ground([(f"s{shot}", [])])
        found = []
        ctl.solve(on_model=lambda m: found.append(True) or False)
        elapsed = time.time() - t0
        if shot == 1:
            setup_time = elapsed          # shot 1 = base solve (its "setup")
        else:
            solve_total += elapsed
        suffix = "" if found else "  (UNSAT)"
        print(f"{shot:<6d} | {len(chunk):<10d} | {cumulative:<12d} | {elapsed:14.3f}{suffix}")

    total = setup_time + solve_total
    print()
    print(f"  total clingo MSS solve time: {solve_total:.3f}s over {len(chunks) - 1} incremental shots")
    print(f"  total clingo MSS wall time (incl. base setup): {total:.3f}s")


if __name__ == "__main__":
    main()
