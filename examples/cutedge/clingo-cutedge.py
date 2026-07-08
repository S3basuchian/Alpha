#!/usr/bin/env python3
"""Drive clingo on the cutedge iterative edge-cutting (retraction) loop.

This is the clingo counterpart to the Alpha live/batch modes in
`IncrementalCutedgeRetractionBenchmark`. The loop is identical and every mode
drives its OWN cut sequence (first-found answer set):

  1. solve for the FIRST answer set;
  2. read its single delete(a,b) atom  -> the edge to cut;
  3. retract that edge;
  4. re-solve. Repeat for numShots shots.

Two clingo modes:

  rebuilt : a fresh clingo.Control each shot, grounded from scratch on
            `encoding + current edge facts`. Every shot re-pays the O(V^3)
            reachability grounding the paper (L&W 2018, Table 2) flags as the
            cutedge bottleneck -- this is the "rebuild from scratch" analogue of
            Alpha `batch`, but on an eager grounder.

  mss     : one long-lived Control. Every actual edge is declared a ground
            `#external edge(a,b)` and set true, and the program is grounded ONCE
            (paying the reachability recursion a single time at base setup).
            Each cut flips the chosen edge's external to false -- a genuine
            multi-shot "retraction" that never re-grounds. This is the clingo
            analogue of Alpha `live`.

Usage:
  clingo-cutedge.py <encoding.lp> <edges.lp> <numShots> <rebuilt|mss>
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
                nodes.add(a)
                nodes.add(b)
    return out, nodes


def chosen_edge(symbols):
    """The (a, b) of the single delete(a, b) atom in the model, or None.

    The encoding admits exactly one delete atom per answer set (choosing any two
    would force a mutual keep and contradict `not keep`), so `first` is unique;
    we still take the min for a deterministic tie-break should that ever change.
    """
    if symbols is None:
        return None
    picks = [
        (s.arguments[0].number, s.arguments[1].number)
        for s in symbols
        if s.name == "delete" and len(s.arguments) == 2
    ]
    return min(picks) if picks else None


def solve_first(ctl):
    """Solve for one answer set; return its symbols, or None if UNSAT."""
    holder = {"syms": None}

    def on_model(m):
        holder["syms"] = m.symbols(atoms=True)
        return False  # stop after the first model

    ctl.solve(on_model=on_model)
    return holder["syms"]


def header():
    print(f"{'shot':<6} | {'active':<10} | {'cut edge':<14} | {'clingo (s)':<12}")
    print(f"{'-'*6}-+-{'-'*10}-+-{'-'*14}-+-{'-'*12}")


def row(shot, active, edge, el):
    label = "(none)" if edge is None else f"edge({edge[0]},{edge[1]})."
    print(f"{shot:<6d} | {active:<10d} | {label:<14} | {el:12.3f}")


def run_rebuilt(encoding, edges, nodes, num_shots):
    print("\n==== clingo (rebuilt from scratch each shot, own sequence) ====")
    header()
    active = set(edges)
    cut_seq = []
    total = 0.0
    for shot in range(num_shots):
        facts = "".join(f"edge({a},{b}). " for (a, b) in active)
        ctl = clingo.Control(["1"])
        t0 = time.time()
        ctl.add("base", [], encoding + "\n" + facts)
        ctl.ground([("base", [])])
        syms = solve_first(ctl)
        el = time.time() - t0
        total += el
        edge = chosen_edge(syms)
        row(shot, len(active), edge, el)
        if edge is None:
            break
        if shot < num_shots - 1:
            active.discard(edge)
            cut_seq.append(edge)
    print(f"\n  total clingo rebuilt time: {total:.3f}s over {len(cut_seq) + 1} shots")
    print(f"  cut sequence: {['edge(%d,%d).' % e for e in cut_seq]}")


def run_mss(encoding, edges, nodes, num_shots):
    print("\n==== clingo MSS (one Control, edges as externals, retract in place) ====")
    ext_decls = "".join(f"#external edge({a},{b}).\n" for (a, b) in edges)
    ctl = clingo.Control(["1"])
    t_setup = time.time()
    ctl.add("base", [], encoding + "\n" + ext_decls)
    ctl.ground([("base", [])])
    for (a, b) in edges:
        ctl.assign_external(clingo.Function("edge", [clingo.Number(a), clingo.Number(b)]), True)
    setup = time.time() - t_setup
    print(f"  base setup ({len(edges)} edge externals, reachability grounded once): {setup:.3f}s\n")
    header()
    cut_seq = []
    total = 0.0
    active = len(edges)
    for shot in range(num_shots):
        t0 = time.time()
        syms = solve_first(ctl)
        el = time.time() - t0
        total += el
        edge = chosen_edge(syms)
        row(shot, active, edge, el)
        if edge is None:
            break
        if shot < num_shots - 1:
            ctl.assign_external(clingo.Function("edge", [clingo.Number(edge[0]), clingo.Number(edge[1])]), False)
            cut_seq.append(edge)
            active -= 1
    print(f"\n  total clingo MSS solve time: {total:.3f}s over {len(cut_seq) + 1} shots")
    print(f"  total clingo MSS wall time (incl. base setup): {setup + total:.3f}s")
    print(f"  cut sequence: {['edge(%d,%d).' % e for e in cut_seq]}")


def main():
    if len(sys.argv) != 5:
        print("Usage: clingo-cutedge.py <encoding.lp> <edges.lp> <numShots> <rebuilt|mss>", file=sys.stderr)
        sys.exit(2)
    encoding_path, edges_path, n_str, mode = sys.argv[1:5]
    num_shots = int(n_str)
    with open(encoding_path) as f:
        encoding = f.read()
    edges, nodes = parse_edges(edges_path)
    if mode == "rebuilt":
        run_rebuilt(encoding, edges, nodes, num_shots)
    elif mode == "mss":
        run_mss(encoding, edges, nodes, num_shots)
    else:
        print(f"Unknown mode: {mode} (use rebuilt|mss)", file=sys.stderr)
        sys.exit(2)


if __name__ == "__main__":
    main()
