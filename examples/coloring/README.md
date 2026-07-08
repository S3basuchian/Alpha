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
├── bench-coloring-sweep.sh  # ★ default: sweep the paper Table-4 instance set
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
