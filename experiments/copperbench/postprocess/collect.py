#!/usr/bin/env python3
"""Collect copperbench results for the AlphaInc incremental benchmarks into a CSV and the
paper's four LaTeX tables.

Usage (from wherever the copperbench <name>/ output folders live — normally the repo root):
    python3 experiments/copperbench/postprocess/collect.py [--results-dir .] [BENCH ...]

with BENCH in {groundexp, cutedge, reach, coloring, coloring-grow, walk} (default: all). For each
benchmark it reads every <name>/<config>/<instance>/run*/ directory, extracts the solver's
self-reported overall runtime (the `RESULT_SECONDS=` line the wrappers print — the same
"overall runtime" the paper reports, excluding JVM/gradle startup); a run that produced no
RESULT_SECONDS was killed by runsolver and is reported as Timeout or Memout — whichever cap (the
wall-clock limit or the RSS+Swap memory limit) it hit first, read from the runsolver log.

Each instance SIZE is run on NUM_SAMPLES independently-seeded random instances (the RESULT_INSTANCE
carries a `-s<seed>` suffix). Aggregation is two-level, matching the paper: within one sample take
the median across any repeated runs, then report the MEAN over the size's samples (the paper
averages over 10 random instances). A cell with some samples unfinished reports the mean over the
finished ones and a `note:` line to stderr; a cell with none finished reports `Timeout` or `Memout`.
When both of a solver's two columns (Alpha MSS+Rebuilt, or clingo Rebuilt+MSS) show the same kind,
they are merged into a single `\multicolumn{2}{c}{<kind>}` in the LaTeX table.

Outputs (into the results dir):
    results_long.csv   one row per (benchmark, config, instance-with-seed, run): seconds / status
    results_wide.csv   mean-over-samples per (benchmark, size) across the four solver columns
    tables.tex         the tables, in the paper's shape, ready to paste
"""
import argparse
import csv
import os
import re
import statistics
import sys
from glob import glob

CONFIG_COLS = ["alpha-mss", "alpha-rebuilt", "clingo-rebuilt", "clingo-mss"]
COL_HEADER = {"alpha-mss": "Alpha MSS", "alpha-rebuilt": "Alpha Rebuilt",
              "clingo-rebuilt": "clingo Rebuilt", "clingo-mss": "clingo MSS"}

# Per-benchmark row spec: ordered (instance_key, display_label, extra_col_or_None).
# instance_key matches the RESULT_INSTANCE the wrapper prints.
SPEC = {
    # Ground explosion, model-dependent forbid-all, maxAS=2 (each shot: enumerate up to 2 answer sets,
    # block ALL selected elements found, re-solve). Own sequence per solver. Instance key "dom-shots";
    # small dom at 10 shots, big dom (500/1000/5000/10000) at 10/20/40 shots.
    "groundexp": {
        "rows": [("8-10", "8", "10"), ("10-10", "10", "10"), ("12-10", "12", "10"),
                 ("14-10", "14", "10"), ("16-10", "16", "10"), ("18-10", "18", "10"),
                 ("20-10", "20", "10"),
                 ("500-10", "500", "10"), ("500-20", "500", "20"), ("500-40", "500", "40"),
                 ("1000-10", "1000", "10"), ("1000-20", "1000", "20"), ("1000-40", "1000", "40"),
                 ("5000-10", "5000", "10"), ("5000-20", "5000", "20"), ("5000-40", "5000", "40"),
                 ("10000-10", "10000", "10"), ("10000-20", "10000", "20"), ("10000-40", "10000", "40")],
        "row_head": r"$|\mathit{dom}|$", "extra_head": "Shots",
        "caption": r"Ground explosion, model-dependent forbid-all ($\mathit{maxAS}=2$).",
        "label": "tab:groundexp",
    },
    "cutedge": {
        "rows": [("100-30", "100/30", None), ("100-50", "100/50", None),
                 ("200-30", "200/30", None), ("200-50", "200/50", None),
                 ("300-10", "300/10", None), ("300-30", "300/30", None),
                 ("500-10", "500/10", None), ("500-30", "500/30", None),
                 ("500-50", "500/50", None)],
        "row_head": r"$|V|/E_\%$", "extra_head": None,
        "caption": "Cutedge benchmark results.", "label": "tab:cutedge",
    },
    # Full base + one edge per shot: each shot re-solves after a single edge is added to a near-full
    # base graph. Sizes {1000/4,1000/8,10000/2,10000/4,10000/8} x shots {10,20,40}. Instance key is
    # "V-Emult-SHOTS" (see run-reach.sh's announce), shots shown as the extra column.
    "reach": {
        "rows": [("1000-4-10", "1000/4", "10"), ("1000-4-20", "1000/4", "20"), ("1000-4-40", "1000/4", "40"),
                 ("1000-8-10", "1000/8", "10"), ("1000-8-20", "1000/8", "20"), ("1000-8-40", "1000/8", "40"),
                 ("10000-2-10", "10000/2", "10"), ("10000-2-20", "10000/2", "20"), ("10000-2-40", "10000/2", "40"),
                 ("10000-4-10", "10000/4", "10"), ("10000-4-20", "10000/4", "20"), ("10000-4-40", "10000/4", "40"),
                 ("10000-8-10", "10000/8", "10"), ("10000-8-20", "10000/8", "20"), ("10000-8-40", "10000/8", "40")],
        "row_head": r"$|V|/E_{mult}$", "extra_head": "Shots",
        "caption": r"Reachability benchmark results (full base + one edge per shot).", "label": "tab:reach",
    },
    "coloring": {
        "rows": [("10-40", r"10/40$^{\dagger}$", None), ("20-80", "20/80", None),
                 ("30-120", "30/120", None), ("40-160", "40/160", None),
                 ("50-200", "50/200", None), ("100-400", "100/400", None),
                 ("400-1600", "400/1600", None), ("1000-4000", "1000/4000", None)],
        "row_head": r"$|V|/|E|$", "extra_head": None,
        "caption": "Graph $5$-coloring benchmark results.", "label": "tab:coloring",
    },
    # Monotone-growth variant: rotation=grow, sizes {1000,2000,4000} x shots {10,20,40}.
    # Instance key is "V-E-SHOTS" (see run-coloring-grow.sh's announce), shots shown as the extra column.
    "coloring-grow": {
        "rows": [("1000-4000-10", "1000/4000", "10"), ("1000-4000-20", "1000/4000", "20"),
                 ("1000-4000-40", "1000/4000", "40"),
                 ("2000-8000-10", "2000/8000", "10"), ("2000-8000-20", "2000/8000", "20"),
                 ("2000-8000-40", "2000/8000", "40"),
                 ("4000-16000-10", "4000/16000", "10"), ("4000-16000-20", "4000/16000", "20"),
                 ("4000-16000-40", "4000/16000", "40")],
        "row_head": r"$|V|/|E|$", "extra_head": "Shots",
        "caption": r"Graph $5$-coloring monotone-growth benchmark results "
                   r"(\texttt{grow}: each shot adds one pendant vertex and edge).",
        "label": "tab:coloring-grow",
    },
    # Gardener's Walk: receding-horizon conformant planning (skittish coupled frogs at 2%/5% of W,
    # 10% walls with an open-disk gardener start, frogs uniform at distance >= 5; every shot: plan
    # h steps, execute one, observe frog hops, re-solve; both Alpha columns use the NAIVE
    # chronological branching heuristic, see run-walk.sh). The W axis shows the static-base scaling
    # trend: rebuilds re-pay the W^2 world model every shot, the session grounds it once. Instance
    # key "W-H-F-SHOTS" (see run-walk.sh's announce); early-UNSAT runs emit no RESULT_SECONDS and
    # count as unfinished samples.
    "walk": {
        "rows": [("100-6-2-20", "100/2", "6"), ("100-12-2-20", "100/2", "12"),
                 ("100-6-5-20", "100/5", "6"), ("100-12-5-20", "100/5", "12"),
                 ("200-6-4-20", "200/4", "6"), ("200-12-4-20", "200/4", "12"),
                 ("200-6-10-20", "200/10", "6"), ("200-12-10-20", "200/10", "12"),
                 ("500-6-10-20", "500/10", "6"), ("500-12-10-20", "500/10", "12"),
                 ("500-6-25-20", "500/25", "6"), ("500-12-25-20", "500/25", "12")],
        "row_head": r"$W$/frogs", "extra_head": "$h$",
        "caption": r"Gardener's Walk: receding-horizon conformant planning ($W{\times}W$ garden, "
                   r"10\% walls, 20 shots; skittish frogs at ${\sim}2\%$ and ${\sim}5\%$ of $W$ "
                   r"placed uniformly at distance ${\geq}5$; lookahead horizon $h$).",
        "label": "tab:walk",
    },
}

RE_KV = {k: re.compile(rf"^RESULT_{k}=(.*)$", re.M)
         for k in ("BENCH", "CONFIG", "INSTANCE", "SECONDS")}

# A run that finishes emits its own RESULT_SECONDS. A run that produced none was killed -- by
# runsolver hitting a cap, or by the OS/SLURM OOM-killer. runsolver enforces a wall-clock time limit
# (-W) whose value it echoes in the startup preamble and whose actual it reports at the end:
#     Enforcing wall clock limit ...: <N> seconds        Real time (s): <wall>
# Only a clear "ran (almost) to the wall limit" signature is a Timeout; every other kill is treated
# as a Memout (RSS at the cap, or an OOM-kill that bypassed runsolver's soft accounting and left no
# clean summary). Parsing the *enforced* wall limit per run keeps this correct whatever -W actually is.
RE_REAL = re.compile(r"^Real time \(s\):\s*([0-9.]+)", re.M)
RE_WALL_LIMIT = re.compile(r"Enforcing wall clock limit[^:]*:\s*(\d+)\s*seconds")

WALL_HIT = 0.95   # wall time >= this fraction of the enforced wall limit => Timeout


def _read(path):
    try:
        with open(path, "r", errors="replace") as fh:
            return fh.read()
    except OSError:
        return ""


def classify_failure(blob):
    """Classify a killed run (no RESULT_SECONDS) as Timeout / Memout from the runsolver log. Only a
    clear ran-to-the-wall-limit signature counts as Timeout; every other kill (RSS at the cap, or an
    early death / OOM-kill that bypassed runsolver's soft accounting) is reported as Memout."""
    real = RE_REAL.search(blob)
    wlim = RE_WALL_LIMIT.search(blob)
    wall = float(real.group(1)) if real else None
    wall_limit = int(wlim.group(1)) if wlim else None    # seconds
    # Ran (almost) to the wall limit -> Timeout (a memout would have been killed earlier).
    if wall is not None and wall_limit and wall >= WALL_HIT * wall_limit:
        return "Timeout"
    return "Memout"  # anything else that got killed -> treat as running out of memory


def parse_run(run_dir):
    """Return (config, instance, seconds_or_None, status) for one run<k>/ directory. A run with no
    RESULT_SECONDS was killed by runsolver -> Timeout or Memout (whichever cap it hit first)."""
    stdout = _read(os.path.join(run_dir, "stdout.log"))
    cfg = RE_KV["CONFIG"].search(stdout)
    inst = RE_KV["INSTANCE"].search(stdout)
    secs = RE_KV["SECONDS"].search(stdout)
    config = cfg.group(1).strip() if cfg else None
    instance = inst.group(1).strip() if inst else None
    if secs:
        return config, instance, float(secs.group(1)), "ok"
    blob = "".join(_read(p) for p in glob(os.path.join(run_dir, "*")) if os.path.isfile(p))
    return config, instance, None, classify_failure(blob)


def collect_benchmark(results_dir, bench):
    """{(config, instance): {'secs': [floats], 'status': [strs], 'runs': [dirs]}} over all runs."""
    agg = {}
    for stdout_path in glob(os.path.join(results_dir, bench, "**", "run*", "stdout.log"),
                            recursive=True):
        run_dir = os.path.dirname(stdout_path)
        config, instance, secs, status = parse_run(run_dir)
        if config is None or instance is None:
            print(f"  warn: could not identify {run_dir} (no RESULT_ lines)", file=sys.stderr)
            continue
        d = agg.setdefault((config, instance), {"secs": [], "status": [], "runs": []})
        d["runs"].append(run_dir)
        d["status"].append(status)
        if secs is not None:
            d["secs"].append(secs)
    return agg


def size_of(instance):
    """Strip the trailing -s<seed> sample suffix to get the instance-SIZE key the SPEC rows use
    (e.g. "100-30-s42" -> "100-30", "1000-4000-10-s47" -> "1000-4000-10")."""
    return re.sub(r"-s\d+$", "", instance)


def sample_value(d):
    """One representative runtime for a single sample: median over its repeated runs, or None."""
    return statistics.median(d["secs"]) if d["secs"] else None


def reduce_by_size(agg):
    """Collapse {(config, instance): {...}} to {(config, size): {'vals', 'n_samples', 'status'}} by
    grouping a size's random samples. 'vals' holds one value per finished sample (median over its
    runs); 'n_samples' counts all samples (so a fully-failed size has 'vals' empty); 'status'
    accumulates every run's status (for the Timeout/Memout kind of an all-failed cell)."""
    by_size = {}
    for (config, instance), d in agg.items():
        b = by_size.setdefault((config, size_of(instance)),
                               {"vals": [], "n_samples": 0, "status": []})
        b["n_samples"] += 1
        v = sample_value(d)
        if v is not None:
            b["vals"].append(v)
        b["status"].extend(d["status"])
    return by_size


def cell(by_size, config, size):
    """One table cell: mean seconds over the size's finished random samples, with a trailing
    ``(N)`` when N of the samples failed (e.g. ``1.23 (3)`` = mean of the 7 finished, 3 timed/mem-out
    — the bracket count does not distinguish the two). If *every* sample failed, the failure kind
    (``Timeout`` or ``Memout``, whichever most samples hit) is shown instead. ``None`` means the
    config was not run for this benchmark."""
    b = by_size.get((config, size))
    if b is None:
        return None  # config not run for this benchmark (e.g. coloring clingo-mss)
    n = b["n_samples"]
    finished = len(b["vals"])
    if finished == 0:
        # Every sample failed: report the kind most samples hit (tie -> Timeout). classify_failure
        # only ever yields Timeout or Memout, so one of these counts is nonzero.
        n_time = b["status"].count("Timeout")
        n_mem = b["status"].count("Memout")
        return "Timeout" if n_time >= n_mem else "Memout"
    mean = statistics.mean(b["vals"])
    failed = n - finished
    if failed == 0:
        return f"{mean:.2f}"
    return f"{mean:.2f} ({failed})"  # mean over finished samples; N samples timed/mem-out


def render_numeric_cells(by_size, key):
    """The four solver columns for one row as a LaTeX fragment. When BOTH of a solver's columns
    (Alpha = MSS+Rebuilt, clingo = Rebuilt+MSS) show the *same* all-failed kind (both Timeout or both
    Memout), they collapse into a single centered ``\\multicolumn{2}{c}{<kind>}``; a column not run
    for the benchmark renders as ``{--}``."""
    raw = [cell(by_size, cfg, key) for cfg in CONFIG_COLS]
    parts = []
    for lo in (0, 2):  # the two solver column-pairs, in CONFIG_COLS order
        a, b = raw[lo], raw[lo + 1]
        if a == b and a in ("Timeout", "Memout"):
            parts.append(rf"\multicolumn{{2}}{{c}}{{{a}}}")
        else:
            parts.append("{--}" if a is None else a)
            parts.append("{--}" if b is None else b)
    return " & ".join(parts)


def latex_table(bench, by_size):
    s = SPEC[bench]
    has_extra = s["extra_head"] is not None
    # Column format: row-head [+ extra] + 4 numeric columns.
    colfmt = "r" + ("l" if has_extra else "") + "rrrr"
    span_start = 3 if has_extra else 2
    out = []
    out.append(r"\begin{table}[t]")
    out.append(r"  \centering")
    out.append(r"  \small")
    out.append(rf"  \caption{{{s['caption']}}}")
    out.append(rf"  \label{{{s['label']}}}")
    out.append(rf"  \begin{{tabular}}{{{colfmt}}}")
    out.append(r"    \toprule")
    lead = "    " + ("& " if not has_extra else "& & ")
    out.append(lead + r"\multicolumn{2}{c}{Alpha} & \multicolumn{2}{c}{clingo} \\")
    out.append(rf"    \cmidrule(lr){{{span_start}-{span_start+1}}}")
    out.append(rf"    \cmidrule(lr){{{span_start+2}-{span_start+3}}}")
    head = f"    {s['row_head']} & "
    if has_extra:
        head += f"{s['extra_head']} & "
    head += "MSS & Rebuilt & Rebuilt & MSS \\\\"
    out.append(head)
    out.append(r"    \midrule")
    # Collapse consecutive rows sharing a row-head label (the shots variants of one size) into a
    # single \multirow spanning cell; \hline separates the multi-row blocks. Tables without a
    # shots column have unique labels, so every group is size 1 and this renders exactly as before.
    groups = []
    for key, disp, extra in s["rows"]:
        if groups and groups[-1][0] == disp:
            groups[-1][1].append((key, extra))
        else:
            groups.append((disp, [(key, extra)]))
    for gi, (disp, members) in enumerate(groups):
        if gi and (len(groups[gi - 1][1]) > 1 or len(members) > 1):
            out.append(r"    \hline")
        n = len(members)
        for mi, (key, extra) in enumerate(members):
            numeric = render_numeric_cells(by_size, key)
            if has_extra:
                head_cell = "" if mi else (rf"\multirow{{{n}}}{{*}}{{{disp}}}" if n > 1 else disp)
                row = f"    {head_cell} & {extra} & " + numeric + r" \\"
            else:
                row = f"    {disp} & " + numeric + r" \\"
            out.append(row)
    out.append(r"    \bottomrule")
    out.append(r"  \end{tabular}")
    if bench == "coloring":
        out.append(r"  \vspace{2pt}")
        out.append(r"  {\footnotesize $^{\dagger}$ UNSAT on every shot}")
    out.append(r"\end{table}")
    return "\n".join(out)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("benches", nargs="*", default=list(SPEC), help="benchmarks (default: all)")
    ap.add_argument("--results-dir", default=".", help="dir holding the copperbench <name>/ folders")
    args = ap.parse_args()
    benches = args.benches or list(SPEC)

    long_rows, wide_rows, tex = [], [], []
    for bench in benches:
        if bench not in SPEC:
            print(f"skip unknown benchmark {bench}", file=sys.stderr)
            continue
        agg = collect_benchmark(args.results_dir, bench)
        if not agg:
            print(f"warn: no runs found for {bench} under {args.results_dir}", file=sys.stderr)
        by_size = reduce_by_size(agg)
        for (config, instance), d in sorted(agg.items()):
            for i, rd in enumerate(d["runs"]):
                sv = d["secs"][i] if i < len(d["secs"]) else ""
                long_rows.append([bench, config, instance, i + 1, sv, d["status"][i], rd])
        for key, disp, _ in SPEC[bench]["rows"]:
            wide_rows.append([bench, disp] + [cell(by_size, c, key) or "" for c in CONFIG_COLS])
        tex.append(latex_table(bench, by_size))
        # Surface partially-finished cells (some samples mem-out): the reported mean is over the
        # finished samples only — never silently hide the dropped ones.
        for (config, size), b in sorted(by_size.items()):
            if b["vals"] and len(b["vals"]) < b["n_samples"]:
                print(f"  note: {bench} {config} {size}: {len(b['vals'])}/{b['n_samples']} "
                      f"samples finished (mean over finished)", file=sys.stderr)

    outdir = args.results_dir
    with open(os.path.join(outdir, "results_long.csv"), "w", newline="") as fh:
        w = csv.writer(fh)
        w.writerow(["benchmark", "config", "instance", "run", "seconds", "status", "run_dir"])
        w.writerows(long_rows)
    with open(os.path.join(outdir, "results_wide.csv"), "w", newline="") as fh:
        w = csv.writer(fh)
        w.writerow(["benchmark", "instance"] + [COL_HEADER[c] for c in CONFIG_COLS])
        w.writerows(wide_rows)
    with open(os.path.join(outdir, "tables.tex"), "w") as fh:
        fh.write(r"% Requires \usepackage{booktabs} and \usepackage{multirow} in the preamble." + "\n")
        fh.write("\n\n".join(tex) + "\n")

    print(f"wrote {os.path.join(outdir, 'results_long.csv')}  ({len(long_rows)} runs)")
    print(f"wrote {os.path.join(outdir, 'results_wide.csv')}  ({len(wide_rows)} rows)")
    print(f"wrote {os.path.join(outdir, 'tables.tex')}  ({len(tex)} tables)")


if __name__ == "__main__":
    main()
