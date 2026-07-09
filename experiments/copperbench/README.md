# Copperbench experiments — AlphaInc incremental benchmarks

Reproduces the benchmark tables of the *AlphaInc: Incremental Lazy Grounding* paper on a
SLURM cluster via [copperbench](https://github.com/tlyphed/copperbench), and collects the runs
into a CSV and the paper's LaTeX tables.

| Paper table | Benchmark | Instance grid | Shots |
|---|---|---|---|
| Table 1 (Ground explosion) | `groundexp` | \|dom\| = 8,10,12,14,16,18,20,500,1000 | 10 (small); 10/20/40 (500, 1000) |
| Table 2 (Cutedge)          | `cutedge`  | \|V\|/E% = 100/30 … 500/50 (9)          | 10 |
| Table 3 (Reachability)     | `reach`    | \|V\|/E_mult = 1000/4 … 10000/8 (5)     | 5  |
| Table 4 (5-coloring, mixed edits) | `coloring` | \|V\|/\|E\| = 10/40 … 1000/4000 (8)     | 20 |
| Table 5 (5-coloring, monotone growth) | `coloring-grow` | \|V\|/\|E\| = 1000/4000, 2000/8000, 4000/16000 | 10, 20, 40 |

`groundexp` is the ground-explosion benchmark under a **model-dependent forbid-all** protocol
(*maxAS = 2*): each shot enumerates up to 2 answer sets and blocks **all** selected elements found
(`:- p(i,…,i).`), then re-solves. Every solver drives its **own** forbid sequence from its own models
(own-sequence, like `cutedge`) — nothing is shared or replayed. Small domains run 10 shots; the two
big domains (500, 1000) run 10/20/40 shots (Shots column), so each (dom, shots) pair is one row.
clingo still eagerly grounds the `p/6` cross-product, so it mem/times-out for \|dom\|≥18 (the L&W
grounding wall) and on both big domains. Because forbid-all is **deterministic**, the seed is only a
timing repetition here (all `NUM_SAMPLES` samples of a size are identical) — run this one with
`NUM_SAMPLES=1` if you want to avoid redundant work.

`coloring` and `coloring-grow` share the same driver and 5-colouring encoding: `coloring` drives
a mixed edit stream (grow / add-edge / retract / constrain) to stress *search*-state reuse, while
`coloring-grow` uses `rotation=grow` (each shot adds one pendant vertex + edge) to isolate
*grounding / atom-store* reuse and sweeps the shot count. Its instances are `V E SHOTS` triples,
so each (size, shots) pair is one table row (Shots column). With the uniform 300 s cap, its largest
cells (4000/16000 at 20/40 shots) may time out on the cluster — those cells then report as
`Timeout` (see the cell format below).

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
| timeout    | **300 s** wall-clock per run (uniform across all benchmarks) |
| memory     | **64 GB** per run — the runsolver cap for the *whole* job (Alpha JVM, or the clingo subprocess whose eager grounding can blow up on cutedge O(V³) / groundexp) |
| java heap  | **`-Xmx60g -XX:MaxRAM=64000M`** — Alpha's Java heap uses the full node memory, kept a few GB under the 64 GB cap so a blow-up trips runsolver (Memout) rather than a premature JVM OOM. Override via `JVM_XMX` |
| samples    | **10** random instances per size (`NUM_SAMPLES`, seeds `BASE_SEED..BASE_SEED+9`, default `42..51`); the postprocessor reports the **mean over the samples** — matching the paper's "averaged over 10 instances" |
| repetitions| **1** run per (config, sample) — the 10 samples are the variance estimate. Raise `runs` in a `*.json.in` for extra timing-noise repetitions; the postprocessor then takes the per-sample median before averaging |
| cores      | **1** per run (single-threaded; fair Alpha-vs-clingo comparison) |
| partition  | **sunnycove** (pins every run to one CPU generation so timings are comparable; override with `PARTITION=…`) |
| use_perf   | **false** (we don't collect perf counters; avoids `perf_event_paranoid` issues) |

Change any of these by editing the `*.json.in` templates and re-running `setup.sh`, or the
generated `*.json` directly. `NUM_SAMPLES` / `BASE_SEED` are env overrides to `setup.sh`
(like `PARTITION`); they control how many seeded instance rows each size expands to.

### Random sampling — same 10 instances for all four solvers

Each instance **size** is run on `NUM_SAMPLES` independently-seeded random instances, and **all
four solver configs see the identical seeded instance** (the seed rides the copperbench instance
line, so the configs×instances grid hands the same `<size> <seed>` to every config). This keeps
the comparison 1:1 per sample. What the seed varies, per benchmark:

| Benchmark | Seed varies | 1:1 across the four solvers because… |
|---|---|---|
| `groundexp` | **nothing** — model-dependent forbid-all (maxAS=2) is deterministic (block every found selection), so the seed is only a timing repetition (the `dom(1..N)` instance is fixed) | each solver drives its **own** forbid sequence from its own models (own-sequence, like `cutedge`); nothing is shared |
| `reach`     | the random directed **graph** (which edges arrive) | all four read the same seeded `edges-rand-…-s<seed>.lp` |
| `coloring` / `coloring-grow` | base graph **+** the model-dependent edit stream | clingo replays Alpha's per-shot program dump, produced from that seed |
| `cutedge`   | the random **graph** only | each solver still drives its **own** answer-set cut sequence on that shared graph (own-sequence methodology — the encoding's `delete` is an arbitrary single edge, so the sequences may diverge; this is intentional and unchanged) |

**Cell format for time/mem-outs.** Each table cell is the mean seconds over a size's finished
samples. If some samples time/mem-out (under the uniform **300 s** cap), the cell shows the mean of
the finished ones followed by the count of failures in brackets, e.g. `1.23 (3)` = mean of the 7
finished samples, 3 timed/mem-out. If **all** samples fail, the cell shows the failure kind
(`Timeout` or `Memout`) instead of a number — never a silent average over a subset.

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
PARTITION=sunnycove bash experiments/copperbench/setup.sh
#    -> builds alpha-cli-app (installDist), pre-generates all instances,
#       renders experiments/copperbench/{groundexp,cutedge,reach,coloring,coloring-grow}.json

# 1. generate the SLURM job trees
copperbench experiments/copperbench/groundexp.json
copperbench experiments/copperbench/cutedge.json
copperbench experiments/copperbench/reach.json
copperbench experiments/copperbench/coloring.json
copperbench experiments/copperbench/coloring-grow.json

# 2. submit (each command creates a <name>/ folder in the CWD)
( cd groundexp     && bash submit_all.sh )
( cd cutedge        && bash submit_all.sh )
( cd reach          && bash submit_all.sh )
( cd coloring       && bash submit_all.sh )
( cd coloring-grow  && bash submit_all.sh )

# 3. once everything has finished, collect results (CSV + LaTeX)
python3 experiments/copperbench/postprocess/collect.py groundexp cutedge reach coloring coloring-grow
#    -> results_long.csv   (one row per (config, seeded-instance, run): seconds / status / run_dir)
#       results_wide.csv   (mean-over-samples per size × the four columns)
#       tables.tex         (the five tables, paper shape, ready to paste)
```

Run `copperbench` and `collect.py` from the same directory (the repo root is convenient), so
the `<name>/` output folders and the `results_*.csv` end up together.

## Reproducibility

Instances are generated deterministically from the sample seed, so a fresh checkout reproduces
identical inputs. Each size expands to `NUM_SAMPLES` rows with seeds `BASE_SEED..BASE_SEED+N-1`
(default `42..51`), and `setup.sh` pre-generates every seeded input file so the parallel SLURM
jobs never race to create one:
- `groundexp`: `dom(1..N)`; model-dependent forbid-all is deterministic, so
  the seed only repeats the timing (each solver drives its own forbid sequence from its own models).
- `cutedge`: random graph per seed (`gen_cutedge.py <V> <pct> <seed>` → `edges-<V>-<pct>-s<seed>.lp`).
- `reach`: random directed graph per seed (`gen-random-graph.py --seed` → `edges-rand-…-s<seed>.lp`).
- `coloring` / `coloring-grow`: base graph generated inside the Java driver (`JavaRandom`, the
  sample seed); the model-dependent edit stream is recorded by Alpha and replayed identically by
  clingo.

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
├── setup.sh                      build jar + expand grids × seeds + generate instances + render *.json
├── <bench>.json.in               copperbench config templates (__REPO_ROOT__/__PARTITION__/…)
├── <bench>.configs               the config tokens (one per line)
├── <bench>.sizes                 committed base size grid (one size per line, no seed)
├── <bench>.instances             GENERATED by setup.sh: <bench>.sizes × seeds (gitignored)
├── wrappers/
│   ├── _common.sh                shared helpers (paths, JVM opts, result emission)
│   └── run-<bench>.sh            runs ONE config for ONE instance, prints RESULT_SECONDS
└── postprocess/collect.py        copperbench output tree -> CSV + tables.tex
```

The wrappers call the existing drivers under `examples/<bench>/` (encodings, `gen_*.py`,
`clingo-*.py`) and the built `alpha-cli-app` classes — those must be present in the checkout.
