# Copperbench experiments — AlphaInc incremental benchmarks

Reproduces the four benchmark tables of the *AlphaInc: Incremental Lazy Grounding* paper on a
SLURM cluster via [copperbench](https://github.com/tlyphed/copperbench), and collects the runs
into a CSV and the paper's LaTeX tables.

| Paper table | Benchmark | Instance grid | Shots |
|---|---|---|---|
| Table 1 (Ground explosion) | `groundexp` | \|dom\| = 8,10,12,14,16,18,20,500,1000 | 3–5 forbid shots |
| Table 2 (Cutedge)          | `cutedge`  | \|V\|/E% = 100/30 … 500/50 (9)          | 10 |
| Table 3 (Reachability)     | `reach`    | \|V\|/E_mult = 1000/4 … 10000/8 (5)     | 5  |
| Table 4 (5-coloring)       | `coloring` | \|V\|/\|E\| = 10/40 … 1000/4000 (8)     | 20 |

Each table has four solver columns, produced by four copperbench *configs*:

| config token     | paper column   | what it runs |
|------------------|----------------|--------------|
| `alpha-mss`      | Alpha **MSS**  | one live `AlphaSession`, state retained across shots (incremental) |
| `alpha-rebuilt`  | Alpha **Rebuilt** | fresh `AlphaSession` rebuilt each shot (batch) |
| `clingo-rebuilt` | clingo **Rebuilt** | clingo re-grounded from scratch each shot |
| `clingo-mss`     | clingo **MSS** | one long-lived clingo `Control`, incremental grounding |

The reported number is each solver's **own overall runtime** summed over the shots (the
`RESULT_SECONDS=` line the wrappers print) — the same quantity the paper reports, and it
excludes JVM/gradle startup. `runsolver` enforces the limits and marks the Timeout/Memout cells.

## Experimental parameters (baked into the `*.json.in` templates)

| | |
|---|---|
| timeout    | **300 s** wall-clock per run |
| memory     | **16 GB** per run |
| repetitions| **3** runs per (config, instance); the postprocessor reports the **median** |
| cores      | **1** per run (single-threaded; fair Alpha-vs-clingo comparison) |
| exclusive  | **true** (whole node per run, for interference-free timing) |
| max_parallel_jobs | **20** (SLURM array throttle `%20`; raise/lower to suit cluster etiquette) |

Change any of these by editing the `*.json.in` templates and re-running `setup.sh`, or the
generated `*.json` directly.

## Prerequisites on the cluster

- Java 17 (only for the one-time `installDist` build; the Gradle wrapper is included).
- `python3` with the **clingo Python module** (`python3 -c 'import clingo'`) **and** the `clingo`
  binary on `PATH` — both are used (rebuilt uses the binary, MSS uses the module).
- `copperbench` installed (`python -m pip install .` from a copperbench checkout).
- `runsolver` and `clearcache` binaries (copperbench defaults: `/opt/runsolver`, `/opt/clearcache`).

## Usage

```bash
# 0. from the repo root, on the cluster head node
#    (override cluster fields if they differ from copperbench's defaults)
PARTITION=broadwell RUNSOLVER=/opt/runsolver CLEARCACHE=/opt/clearcache \
    bash experiments/copperbench/setup.sh
#    -> builds alpha-cli-app (installDist), pre-generates all instances,
#       renders experiments/copperbench/{groundexp,cutedge,reach,coloring}.json

# 1. generate the SLURM job trees
copperbench experiments/copperbench/groundexp.json
copperbench experiments/copperbench/cutedge.json
copperbench experiments/copperbench/reach.json
copperbench experiments/copperbench/coloring.json

# 2. submit (each command creates a <name>/ folder in the CWD)
( cd groundexp && bash submit_all.sh )
( cd cutedge   && bash submit_all.sh )
( cd reach     && bash submit_all.sh )
( cd coloring  && bash submit_all.sh )

# 3. once everything has finished, collect results (CSV + LaTeX)
python3 experiments/copperbench/postprocess/collect.py groundexp cutedge reach coloring
#    -> results_long.csv   (one row per run: seconds / status / run_dir)
#       results_wide.csv   (median per instance × the four columns)
#       tables.tex         (the four tables, paper shape, ready to paste)
```

Run `copperbench` and `collect.py` from the same directory (the repo root is convenient), so
the `<name>/` output folders and the `results_*.csv` end up together.

## Reproducibility

Instances are generated deterministically, so a fresh checkout reproduces identical inputs:
- `groundexp`: `dom(1..N)`; forbid order = `random.Random(42)` scatter permutation, handed
  identically to all four solvers (their per-shot answer-set counts must agree — a cross-check).
- `cutedge`: random graph, **seed 42** (`gen_cutedge.py`).
- `reach`: random directed graph, **seed 0** (`gen-random-graph.py`).
- `coloring`: base graph generated inside the Java driver (`JavaRandom`, **seed 42**); the
  model-dependent edit stream is recorded by Alpha and replayed identically by clingo.

## Coloring clingo-MSS

All four columns of every table are produced, including `coloring` clingo-MSS. Since the repo had
no multi-shot clingo driver for colouring, `examples/coloring/clingo-coloring-mss.py` was added: it
reconstructs each shot's edit by diffing consecutive program dumps and replays them against one
long-lived clingo `Control` (vertices grounded once as they grow, edges as toggleable `#external`
so retraction flips them false, forbid constraints `:- cK(v).` grounded as they appear). Validated
to produce the identical per-shot SAT/UNSAT sequence as the rebuilt driver.

## Layout

```
experiments/copperbench/
├── README.md                     this file
├── setup.sh                      build jar + generate instances + render *.json
├── <bench>.json.in               copperbench config templates (__REPO_ROOT__/__PARTITION__/…)
├── <bench>.configs               the config tokens (one per line)
├── <bench>.instances             the instance parameters (one per line)
├── wrappers/
│   ├── _common.sh                shared helpers (paths, JVM opts, result emission)
│   └── run-<bench>.sh            runs ONE config for ONE instance, prints RESULT_SECONDS
└── postprocess/collect.py        copperbench output tree -> CSV + tables.tex
```

The wrappers call the existing drivers under `examples/<bench>/` (encodings, `gen_*.py`,
`clingo-*.py`) and the built `alpha-cli-app` classes — those must be present in the checkout.
