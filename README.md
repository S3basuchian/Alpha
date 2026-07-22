# Alpha

[![Latest DOI](https://zenodo.org/badge/62882005.svg)](https://zenodo.org/badge/latestdoi/62882005)
[![Travis-CI Build Status](https://travis-ci.com/alpha-asp/Alpha.svg?branch=master)](https://travis-ci.com/alpha-asp/Alpha)
[![AppVeyor Build Status](https://ci.appveyor.com/api/projects/status/github/alpha-asp/alpha?svg=true&branch=master)](https://ci.appveyor.com/project/lorenzleutgeb/alpha)
[![codecov](https://codecov.io/gh/alpha-asp/Alpha/branch/master/graph/badge.svg)](https://codecov.io/gh/alpha-asp/Alpha)
[![Code Quality Status](https://codebeat.co/badges/10b609be-9774-42a1-b7fe-2bb64382744d)](https://codebeat.co/projects/github-com-alpha-asp-alpha-master)
[![Coverage Status](https://coveralls.io/repos/github/alpha-asp/Alpha/badge.svg?branch=master)](https://coveralls.io/github/alpha-asp/Alpha?branch=master)

Alpha is an [Answer Set Programming (ASP)](https://en.wikipedia.org/wiki/Answer_set_programming) system: It reads a
logic program (a set of logical rules) and computes the corresponding answer sets. ASP falls into the category of
declarative and logic programming. Its applications are solving combinatorial problems, but it also is a good tool for
reasoning in the context of knowledge-representation and databases.

Alpha is the successor of [OMiGA](http://www.kr.tuwien.ac.at/research/systems/omiga/) and currently in development.
In contrast to many other ASP systems, Alpha implements a *lazy-grounding* approach in hopes of overcoming memory
constraints when working with large input.

Alpha is not the fastest system available, since its goal is not to be the fastest system with current technology but
to explore new technologies rapidly. Those technologies, like lazy-grounding, allow Alpha to succeed where other ASP
systems fail completely. The project deliberately chooses to trade shorter execution times (which would be possible by
using unmanaged runtimes, e.g. C/C++, and low-level optimization) for a more straight forward system design and
possibilities to interface with the ecosystem built around the Java Virtual Machine.

## Getting Started

Download a current version of the distribution jar (`alpha-cli-app-${version}-bundled.jar`) from [Releases](https://github.com/alpha-asp/Alpha/releases) and save it as `alpha.jar` for convenience.

Running Alpha is as simple as running any other JAR:

```bash
$ java -jar alpha.jar
```

### Example Usage

Solve 3-colorability for some benchmarking instance and filter for color predicates:

```bash
$ java -jar alpha.jar -i benchmarks/omiga/omiga-testcases/3col/3col-10-18.txt -fblue -fred -fgreen
```

Note that in this example the path to the input file is relative to the root of this repository. If you have not checked out the repository, you can just [download the example file from GitHub](/benchmarks/omiga/omiga-testcases/3col/3col-10-18.txt).

[A coder's guide to answer set programming](https://madmike200590.github.io/asp-guide/) provides a short and high-level tutorial on Answer Set Programming.

## Building

Alpha uses the [Gradle build automation system](https://gradle.org). Executing

```bash
$ ./gradlew build
```

will automatically fetch all dependencies (declared in [`build.gradle.kts`](build.gradle.kts)) and compile the project.

Artifacts generated will be placed in the `build/` subfolder of the respective module. Most notably you'll find files ready for distribution at
`alpha-cli-app/build/distributions/`. They contain archives which in turn contain a `bin/` directory with scripts to run Alpha on Linux
and Windows.

If you want to generate a JAR file to be run standalone, execute

```bash
$ ./gradlew alpha-cli-app:bundledJar
```

and pick up `alpha-cli-app/build/libs/alpha-cli-app-${version}-bundled.jar`.

### A Note on IDEs

We have contributors using [IntelliJ IDEA](https://www.jetbrains.com/idea/) as well as [Eclipse IDE](https://www.eclipse.org/).
However, we decided to not check in files related to project configuration. For both tools, standard features to "import"
the project based on its Gradle build configuration are available, and they will infer sane defaults. If you
run into trouble feel free to file an issue.

## Incremental multi-shot solving & benchmarks

This fork adds an **incremental (multi-shot) solving engine** to Alpha and a benchmark suite that
measures it against batch (rebuild-from-scratch) Alpha and against [clingo](https://potassco.org/clingo/).
A running `AlphaSession` keeps its ground program, learned nogoods and heuristic state across solves, so
facts, rules and constraints can be added — and facts retracted — between shots without regrounding the
whole program. The public API is demonstrated by
[`IncrementalSolvingExample`](alpha-cli-app/src/main/java/at/ac/tuwien/kr/alpha/app/examples/IncrementalSolvingExample.java)
(run `./gradlew :alpha-cli-app:runIncrementalExample`, see [`examples/incremental.md`](examples/incremental.md)).

### The benchmarks

Five problem **domains** yield six **benchmarks** (colouring supplies two). Each is a multi-shot workload
where one long-lived incremental session is expected to beat re-grounding every shot:

| Domain | Benchmark(s) | Alpha driver (`…app.examples.*`) | What it stresses |
|---|---|---|---|
| **reach** | `reach` | `IncrementalReachBenchmark` | streamed edge additions to single-source reachability |
| **cutedge** | `cutedge` | `IncrementalCutedgeRetractionBenchmark` | iterative edge **retraction** over transitive closure (L&W 2018 Table 2) |
| **groundexp** | `groundexp` | `IncrementalGroundExpModelForbidBenchmark` | fixed universe with a ground-explosion rule (L&W Example 1), forbidding models shot-by-shot |
| **coloring** | `coloring`, `coloring-grow` | `IncrementalColoringBenchmark` | 5-colouring under a rotating mixed edit stream (`coloring`) or monotone growth (`coloring-grow`) |
| **gardener** | `gardener` | `GardenerBenchmark` | receding-horizon conformant planning (plan *h* / act 1 / observe / re-solve) |

Every benchmark is run in four **configurations**:

| Config | Meaning |
|---|---|
| `alpha-mss` | one live `AlphaSession`, state retained across shots (the incremental engine) |
| `alpha-rebuilt` | a fresh `AlphaSession` rebuilt from scratch each shot (batch baseline) |
| `clingo-rebuilt` | clingo re-grounded from scratch each shot |
| `clingo-mss` | one long-lived clingo `Control`, incremental grounding |

Everything lives under two directories:

- [`examples/<domain>/`](examples/) — the encoding (`encoding.lp`), the instance generator (`gen_*.py`),
  the clingo baseline scripts, and local sweep scripts (`bench-*.sh`).
- [`experiments/copperbench/`](experiments/copperbench/) — the automated [copperbench](https://github.com/nrother/copperbench)/SLURM
  harness that reproduces the paper tables.

Generated instances (`examples/<domain>/instances/`) are **not committed** — they are regenerated
byte-identically from a fixed seed by the commands below (and by `setup.sh`).

### Prerequisites

- **Java 17** and the built distribution — build it once:
  ```bash
  ./gradlew :alpha-cli-app:installDist
  ```
  This produces the runtime classpath used by the raw-`java` drivers below. For convenience:
  ```bash
  export CP='alpha-cli-app/build/install/alpha-cli-app/lib/*'
  export PKG=at.ac.tuwien.kr.alpha.app.examples
  ```
- **Python 3** with the `clingo` module (`pip install clingo`) **and** the `clingo` binary on `PATH` —
  only needed for the clingo baseline columns.

### Running a benchmark locally (step by step)

Each domain follows the same three steps: **(1) generate an instance → (2) run the Alpha driver
(live vs. batch) → (3) optionally run the clingo baselines.**

#### reach
```bash
# 1. generate a random graph (V vertices, E edges, seed)
python3 examples/reach/gen-random-graph.py --vertices 1000 --edges 4000 --seed 42 \
    > examples/reach/instances/edges-rand-v1000-e4000-s42.lp

# 2. run Alpha — args: <encoding> <edges> <SHOTS> <mode>, mode ∈ {live,rebuild,batch}
#    (the gradle task runs from the module dir, so pass ABSOLUTE paths)
./gradlew :alpha-cli-app:runIncrementalReachBenchmark \
    --args="$PWD/examples/reach/encoding.lp $PWD/examples/reach/instances/edges-rand-v1000-e4000-s42.lp 25 live"

# 3. clingo baseline (mss add+ground). Add --one-edge to match the copperbench protocol.
python3 examples/reach/clingo-multishot.py examples/reach/encoding.lp \
    examples/reach/instances/edges-rand-v1000-e4000-s42.lp 25

# four-way sweep over the paper grid:
bash examples/reach/bench-reach-sweep.sh
```

#### cutedge
```bash
# 1. generate — args: <numVertices> <percentEdges> [seed] (needs V>99); writes instances/edges-<V>-<pct>.lp
python3 examples/cutedge/gen_cutedge.py 100 30 42

# 2. run Alpha (computes live and batch in one JVM) — args: <encoding> <edges> <numShots>
./gradlew :alpha-cli-app:runIncrementalCutedgeRetractionBenchmark \
    --args="examples/cutedge/encoding.lp examples/cutedge/instances/edges-100-30.lp 10"

# 3. clingo baselines — last arg ∈ {rebuilt, mss}
python3 examples/cutedge/clingo-cutedge.py examples/cutedge/encoding.lp \
    examples/cutedge/instances/edges-100-30.lp 10 rebuilt

# four-way sweep over the L&W Table-2 sizes:
bash examples/cutedge/bench-cutedge-sweep.sh
```

#### groundexp
```bash
# 1. generate the universe dom(1..N)
python3 examples/groundexp/gen_dom.py 18 > examples/groundexp/instances/dom-18.lp

# 2. run Alpha — -Dge.mode ∈ {mss (live), rebuilt (batch)};
#    args: <encoding> <dom> <shots> <maxAnswerSets> <seed>
java -cp "$CP" -Dge.forbidAll=true -Dge.mode=mss $PKG.IncrementalGroundExpModelForbidBenchmark \
    examples/groundexp/encoding.lp examples/groundexp/instances/dom-18.lp 10 2 42

# 3. clingo baseline — last arg ∈ {rebuilt, mss}
python3 examples/groundexp/clingo-model-forbid.py examples/groundexp/encoding.lp \
    examples/groundexp/instances/dom-18.lp 10 2 rebuilt
```
(Size grids for groundexp are swept via copperbench rather than a local `bench-*.sh` script.)

#### coloring / coloring-grow
```bash
# Instances are generated internally by the driver from the (V, E, seed) args — no instance files.
# args: <V> <E> <SHOTS> <seed>; the driver reports both live and batch.

# coloring — rotating mixed edit stream (default rotation=mix):
./gradlew :alpha-cli-app:runIncrementalColoringBenchmark --args="1000 4000 20 42"

# coloring-grow — monotone pendant growth:
./gradlew :alpha-cli-app:runIncrementalColoringBenchmark -Dcoloring.rotation=grow --args="1000 4000 20 42"

# clingo baselines: add -DclingoDumpDir=/tmp/coldump to the run above to dump per-shot programs,
# then replay them (rebuilt vs mss):
python3 examples/coloring/clingo-coloring.py     /tmp/coldump   # clingo-rebuilt
python3 examples/coloring/clingo-coloring-mss.py /tmp/coldump   # clingo-mss

# local sweeps:
bash examples/coloring/bench-coloring-sweep.sh        # coloring (Table 4)
bash examples/coloring/bench-coloring-grow-sweep.sh   # coloring-grow (Table 5)
```

#### gardener
```bash
# 1. generate a garden spec — args: W numFrogs seed wallPct dmin [openR=3] [nearDist=0]
python3 examples/gardener/gen_gardener_instance.py 100 2 42 10 5 3 > /tmp/gardener.spec

# 2. run Alpha — args: <mode> <W> <H> <F> <SHOTS> <seed> <spec>, mode ∈ {live,batch}
java -cp "$CP" $PKG.GardenerBenchmark live 100 6 2 20 42 /tmp/gardener.spec

# 3. clingo baseline — first arg ∈ {batch, mss}
python3 examples/gardener/gardener_clingo.py batch 100 6 2 20 42 /tmp/gardener.spec
```

### Running the whole suite with copperbench (automated, SLURM)

The [`experiments/copperbench/`](experiments/copperbench/) harness reproduces the paper tables across all
six benchmarks × four configs × a seed grid, on a SLURM cluster. Per benchmark it reads three committed
inputs — `<bench>.sizes` (the size grid), `<bench>.configs` (the four configs) and `<bench>.json.in`
(the copperbench template) — and generates the rest.

**Extra prerequisites:** [`copperbench`](https://github.com/nrother/copperbench), plus `runsolver` and
`clearcache` on the cluster (defaults `/opt/runsolver`, `/opt/clearcache`).

```bash
# 1. Set up: build the dist, expand <bench>.sizes × seeds into <bench>.instances, pre-generate every
#    seeded input file deterministically, and render <bench>.json from <bench>.json.in.
#    Knobs: PARTITION (SLURM partition), BASE_SEED (default 42), NUM_SAMPLES (default 10).
PARTITION=<your-partition> BASE_SEED=42 NUM_SAMPLES=10 bash experiments/copperbench/setup.sh

# 2. Submit each benchmark to the cluster.
for b in groundexp cutedge reach coloring coloring-grow gardener; do
    copperbench experiments/copperbench/$b.json
    ( cd "$b" && bash submit_all.sh )
done

# 3. After all jobs finish, aggregate the results.
python3 experiments/copperbench/postprocess/collect.py groundexp cutedge reach coloring coloring-grow gardener
```

`collect.py` walks the per-run `stdout.log` files, reads the canonical `RESULT_SECONDS=<float>` each wrapper
prints (classifying missing results as Timeout vs. Memout from the runsolver log), takes the median over a
sample's repeats and the mean over each size's seeds, and writes `results_long.csv`, `results_wide.csv` and
`tables.tex` (paper-shape LaTeX; needs `booktabs` + `multirow`).

To change the size grid, edit `experiments/copperbench/<bench>.sizes`; to change how many seeds per size,
set `NUM_SAMPLES`/`BASE_SEED`. [`experiments/copperbench/run-all.sh`](experiments/copperbench/run-all.sh)
wraps steps 1–2 into a single command.

## Suggested Reading

### Beginners' Tutorials
 * [A coder's guide to answer set programming](https://madmike200590.github.io/asp-guide/)

### Theoretical Background and Language Specification
 * [Answer Set Programming: A Primer](http://www.kr.tuwien.ac.at/staff/tkren/pub/2009/rw2009-asp.pdf)
 * [ASP-Core-2 Input Language Format](https://www.mat.unical.it/aspcomp2013/files/ASP-CORE-2.01c.pdf)
 * [Conflict-Driven Answer Set Solving: From Theory to Practice](http://www.cs.uni-potsdam.de/wv/pdfformat/gekasc12c.pdf)
 * [Learning Non-Ground Rules for Answer-Set Solving](http://kr.irlab.org/sites/10.56.35.200.gttv13/files/gttv13.pdf#page=31)

### Research Papers on Alpha

Peer-reviewed publications part of journals, conferences and workshops:

#### 2021

 * [Solving Configuration Problems with ASP and Declarative Domain-Specific Heuristics](http://ceur-ws.org/Vol-2945/21-RT-ConfWS21_paper_4.pdf)

#### 2020

 * [Conflict Generalisation in ASP: Learning Correct and Effective Non-Ground Constraints](https://doi.org/10.1017/S1471068420000368)
 * [Advancing Lazy-Grounding ASP Solving Techniques - Restarts, Phase Saving, Heuristics, and More](https://doi.org/10.1017/S1471068420000332)

#### 2019

 * [Exploiting Partial Knowledge in Declarative Domain-Specific Heuristics for ASP](https://doi.org/10.4204/EPTCS.306.9) ([supplementary material](https://git-ainf.aau.at/DynaCon/website/tree/master/supplementary_material/2019_ICLP_Domain-Specific_Heuristics))
 * [Degrees of Laziness in Grounding: Effects of Lazy-Grounding Strategies on ASP Solving](https://doi.org/10.1007/978-3-030-20528-7_22) ([preprint](https://arxiv.org/abs/1903.12510) | [supplementary material](https://git-ainf.aau.at/DynaCon/website/tree/master/supplementary_material/2019_LPNMR_Degrees_of_Laziness))

#### 2018

 * [Exploiting Justifications for Lazy Grounding of Answer Set Programs](https://doi.org/10.24963/ijcai.2018/240)
 * [Lazy Grounding for Dynamic Configuration: Efficient Large-Scale (Re)Configuration of Cyber-Physical Systems with ASP](https://doi.org/10.1007/s13218-018-0536-x)

#### 2017

 * [Blending Lazy-Grounding and CDNL Search for Answer-Set Solving](https://doi.org/10.1007/978-3-319-61660-5_17) ([preprint](http://www.kr.tuwien.ac.at/research/systems/alpha/blending_lazy_grounding.pdf))
 * [Introducing Heuristics for Lazy-Grounding ASP Solving](https://sites.google.com/site/paoasp2017/Taupe-et-al.pdf)
 * [Lazy-Grounding for Answer Set Programs with External Source Access](https://doi.org/10.24963/ijcai.2017/141)
 * [Techniques for Efficient Lazy-Grounding ASP Solving](https://doi.org/10.1007/978-3-030-00801-7_9) ([technical report](https://www.uni-wuerzburg.de/fileadmin/10030100/Publications/TR_Declare17.pdf#page=131))

Others (e.g. non-peer-reviewed publications, less formal articles, newsletters):

 * [The Alpha Solver for Lazy-Grounding Answer-Set Programming](https://www.cs.nmsu.edu/ALP/2019/04/the-alpha-solver-for-lazy-grounding-answer-set-programming/)

## Similar Work

 * [Smodels](http://www.tcs.hut.fi/Software/smodels/), a solver usually used in conjunction with lparse.
 * [DLV](http://www.dlvsystem.com/dlv/)
 * [ASPeRiX](http://www.info.univ-angers.fr/pub/claire/asperix/), a solver that implements grounding-on-the-fly.
