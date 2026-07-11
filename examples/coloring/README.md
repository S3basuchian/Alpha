# Graph 5-Colouring — Incremental Mixed-Edit Stream (Alpha vs. clingo)

Graph *k*-colouring is the search-hard benchmark from Leutgeb & Weinzierl 2018 —
*"no grounding problem but requires efficient search"*. Where
[groundexp](../groundexp/README.md) and [cutedge](../cutedge/README.md) stress
lazy grounding, and [reach](../reach/README.md) stresses grounder-state
retention on streaming Datalog, **coloring stresses the incremental solver's
ability to keep its learned state (nogoods + VSIDS) useful across a graph that
is edited in every possible way between shots**.

This directory drives 5-colouring through a **rotating mixed edit stream** so a
single instance exercises the full incremental machinery — fact addition, fact
**retraction**, and constraint addition — and compares incremental Alpha against
from-scratch Alpha and rebuilt clingo on the *identical* edit sequence.

## Layout

```
examples/coloring/
├── encoding.lp              # the 5-colouring rules (one predicate per colour)
├── gen_coloring.py          # emit the base graph facts (faithful java.util.Random port)
├── clingo-coloring.py       # clingo driver: solve each dumped shot from scratch (rebuilt)
├── bench-coloring-sweep.sh       # ★ default 1: mixed-edit "Table 4" colourability test
├── bench-coloring-grow-sweep.sh  # ★ default 2: monotone-growth grounding/setup-reuse test
└── README.md                # this file
```

The benchmark itself is the Java driver
`IncrementalColoringBenchmark` (`alpha-cli-app`), which both **produces** the
model/RNG-dependent edit stream (live session) and **replays** it (batch +
clingo dumps), so every mode solves the exact same program each shot.

## Prerequisites

```sh
./gradlew :alpha-cli-app:installDist   # the sweep invokes the built jars directly
```

## The encoding

5 colours in the Alpha one-predicate-per-colour idiom — each colour guessed via
mutual negation over the other four (exactly-one-colour is encoded directly in
the guess), plus a proper-colouring constraint per colour:

```prolog
c1(V) :- v(V), not c2(V), not c3(V), not c4(V), not c5(V).
...                                              % c2..c5 symmetric
:- e(V,W), c1(V), c1(W).                         % adjacent vertices differ
...                                              % one per colour
```

Both parts are graph-independent (quantified over `v/1`, `e/2`), so they are the
fixed rule base while the graph streams in as `v/1` + `e/2` facts. This mirrors
`colorRules()` in the driver exactly.

## The loop (rotating protocol)

After the base solve (shot 1), each subsequent shot applies **exactly one** edit,
cycling through four operation types:

1. **grow** — add a pendant vertex + one edge to a random existing vertex
   (`v(n). e(nb,n).`);
2. **add-edge** — add one edge between two existing vertices (`e(a,b).`);
3. **retract** — remove one currently-present edge (`removeFacts("e(a,b).")`);
4. **constrain** — forbid the colour the current model gives some vertex
   (`:- cK(v).`), i.e. "user rejects this colour, recolour".

If an op is inapplicable (retract with no edges, add-edge on a complete graph) it
falls back to **grow**, so every shot does real work. Growth keeps the instance
broadly satisfiable while add-edge/constrain push it toward conflict; the run
stops at the first UNSAT shot. `-Dcoloring.rotation=` selects `mix` (default),
`grow`, `monotone`, or `noconstrain`.

## The modes

| column          | what it is                                                                              |
|-----------------|------------------------------------------------------------------------------------------|
| **Alpha MSS**   | one long-lived `AlphaSession`; grounder/store/VSIDS warm across shots, with a forced foundedness + VSIDS reset every shot (keeps the warm session sound under the mixed edits). Produces the edit stream. |
| **Alpha Rebuilt** | fresh session rebuilt from the current v/e/constraint state each shot; replays the same stream. |
| **clingo (rebuilt)** | each replayed shot's full program is dumped (`-DclingoDumpDir`) and solved from scratch by clingo. |

Only the `solve`-first call is timed (matching the other incremental benchmarks).
Each solve runs under a per-shot wall-clock cap (`[perShotTimeoutSec]`, default
300 s); a timed-out live solve stops the live sequence, batch/clingo shots are
independent.

## Running

Default sweep — the paper Table-4 instance set (degree-8 random graphs,
`|E| = 4·|V|`), 20 shots, seed 42, four-way where clingo is available:

```sh
./bench-coloring-sweep.sh
RUN_CLINGO=0 ./bench-coloring-sweep.sh              # Alpha MSS-vs-Rebuilt only
INSTANCES="10/40 1000/4000" SHOTS=20 ./bench-coloring-sweep.sh
```

A single instance via gradle (prints the per-shot table):

```sh
./gradlew :alpha-cli-app:runIncrementalColoringBenchmark --args="1000 4000 20 42"
```

## Results (paper Table 4)

Overall runtime in seconds over 20 shots (rotating grow / add-edge / retract /
constrain; the identical edit stream replayed by every solver):

| \|V\|/\|E\|   | Alpha MSS | Alpha Rebuilt | clingo Rebuilt | clingo MSS |
|-----------|-----------|---------------|----------------|------------|
| 10/40 †   | 0.84      | 0.62          | 0.03           | 0.00       |
| 20/80     | 0.13      | 0.28          | 0.03           | 0.01       |
| 30/120    | 0.15      | 0.35          | 0.04           | 0.01       |
| 40/160    | 0.16      | 0.47          | 0.05           | 0.01       |
| 50/200    | 0.14      | 0.47          | 0.05           | 0.01       |
| 100/400   | 0.22      | 0.69          | 0.10           | 0.01       |
| 400/1600  | 0.76      | 3.65          | 0.38           | 0.05       |
| 1000/4000 | 2.04      | 16.77         | 1.03           | 0.12       |

† UNSAT on every shot (near-complete graph); no warm state to reuse, so MSS is
no faster than Rebuilt.

Two takeaways: (i) incremental Alpha's MSS/Rebuilt speedup **grows with instance
size** (≈4.8× at 400/1600, ≈8× at 1000/4000) as the warm learned state amortises
over a heavier per-shot solve; (ii) clingo — with no grounding bottleneck here —
is faster in absolute terms, so this benchmark is about *Alpha-live vs
Alpha-rebuilt*, i.e. the value of retaining solver state across mixed edits, not
about beating clingo.

## Monotone-growth variant (`bench-coloring-grow-sweep.sh`)

The mixed-edit test above stresses **search** reuse under model-invalidating
edits. This second default test isolates the complementary axis — **grounding /
atom-store reuse** — under a purely **monotone** edit stream (`rotation=grow`):
every shot adds one pendant vertex + one edge, which can never invalidate the
current colouring.

On the standard degree-8 random instances (`|E| = 4·|V|`, below the 5-colouring
phase transition, so *easy* to colour) the per-shot cost is dominated not by
search but by **building the ground program** — grounding + atom store + nogood
store, `O(|V|+|E|)`. `Alpha Rebuilt` re-pays that full build **every shot**; the
live session builds it **once** and re-solves warm (reusing grounder + atom store
+ learned nogoods). So the live advantage is setup reuse, and it grows along two
axes:

* **instance size** raises the per-shot ratio (batch-per-shot ÷ live-grow-shot):
  ≈15× at \|V\|=1000, ≈25× at 2000, ≈32× at 4000 (bigger one-time build to reuse);
* **shot count** amortises the one-time base solve toward that ratio.

The sweep runs `rotation=grow` over sizes {1000, 2000, 4000} (`|E| = 4·|V|`) ×
shot counts {10, 20, 40}, timing live vs a batch replay of the identical grow
sequence:

```sh
./bench-coloring-grow-sweep.sh
SIZES="1000" SHOTS_LIST="10 20" ./bench-coloring-grow-sweep.sh   # quick subset
```

### Results (Apple Silicon, OpenJDK 17, clingo 5.8.0, seed 42)

Overall runtime in seconds over the shot count; the identical grow sequence is
replayed by every solver:

| \|V\|/\|E\|    | shots | Alpha MSS | Alpha Rebuilt | clingo Rebuilt | clingo MSS |
|------------|------:|----------:|--------------:|---------------:|-----------:|
| 1000/4000  |    10 |     1.73  |         8.61  |          0.42  |      0.39  |
| 1000/4000  |    20 |     1.83  |        17.91  |          0.83  |      0.45  |
| 1000/4000  |    40 |     2.15  |        33.62  |          1.65  |      0.54  |
| 2000/8000  |    10 |     6.07  |        33.48  |          0.90  |      0.83  |
| 2000/8000  |    20 |     6.23  |        68.29  |          1.75  |      0.95  |
| 2000/8000  |    40 |     7.17  |       137.21  |          3.65  |      1.14  |
| 4000/16000 |    10 |    26.19  |       175.58  |          2.17  |      1.78  |
| 4000/16000 |    20 |    26.07  |       290.68  |          4.17  |      2.00  |
| 4000/16000 |    40 |    27.04  |       658.83  |          8.69  |      2.52  |

The mechanism is visible in the numbers: **the two MSS/incremental modes are
nearly flat in the shot count** (they pay the base build once, then grow-shots
are almost free — Alpha MSS 26.2→27.0 s, clingo MSS 1.78→2.52 s from 10→40
shots at \|V\|=4000), while **both Rebuilt baselines scale linearly** (they
re-build every shot — Alpha Rebuilt 175→659 s, clingo Rebuilt 2.2→8.7 s).

For **Alpha**, MSS-over-Rebuilt is the headline: ~5–6.7× at 10 shots, crossing a
magnitude by 20 shots (9.8–11.2×) and reaching **15–24×** at 40 shots (rising
with both size and shots). clingo shows the same incremental effect (MSS ~3× its
own Rebuilt) but is faster in absolute terms — these instances have no grounding
bottleneck, so clingo pays no |dom|-style explosion; the benchmark's point is the
*value of retaining state across monotone growth*, i.e. MSS vs Rebuilt within
each solver, not Alpha vs clingo.
