# Cutedge: Iterative Edge-Cutting via Retraction (Alpha vs. clingo, four-way)

The *cutedge* benchmark is the showcase example from Leutgeb &amp; Weinzierl 2018
where lazy grounding has a structural advantage over eager grounding. It pairs
a one-edge-removal choice with a transitive-closure reachability check whose
recursive rule grounds to O(V³) instances under eager grounding but only to the
actually-reachable subset under lazy grounding. clingo's `--stats` makes the
point on a 100-node graph — ~16 million ground rules, almost all of its time
spent grounding rather than solving. Lazy grounding never produces them.

This directory drives cutedge as an **iterative edge-cutting** loop that
exercises Alpha's fact **retraction** path, and compares **four** solvers on the
identical loop: incremental Alpha, from-scratch Alpha, rebuilt clingo, and
multi-shot clingo.

## Layout

```
examples/cutedge/
├── encoding.lp                    # the rules (ASP-Core2)
├── gen_cutedge.py                 # generate random edge instances
├── instances/
│   ├── edges-100-30.lp            # 100-node, ~30% density (2 888 edges)
│   ├── edges-100-50.lp            # 100-node, ~50% density (4 864 edges)
│   └── edges-{200,300,500}-*.lp   # larger graphs (L&W 2018 Table 2 sizes)
├── clingo-cutedge.py              # clingo driver: rebuilt + multi-shot (MSS) modes
├── bench-cutedge-sweep.sh         # ★ default: four-way sweep over the Table-2 set
└── README.md                      # this file
```

Instance files are extracted from the Omiga test corpus already shipped in
`alpha-core/benchmarks/omiga/omiga-testcases/cutedge/`. The nine sizes match
L&amp;W 2018 Table 2 (`100/30 … 500/50`, vertices/edge-density %).

## Prerequisites

```sh
./gradlew :alpha-cli-app:installDist   # the sweep invokes the built jars directly
```

## The loop

The loop is the one a user would actually run when consuming the solver's
suggestion:

1. solve cutedge on the current graph — the answer set marks exactly one edge
   as deleted (the `delete(a,b)` atom; the `edge(a,b)` fact is always present);
2. *physically* retract that edge;
3. re-solve — the solver picks the next edge to cut; repeat for `numShots` shots.

**Every mode drives its own cut sequence.** At each shot a mode retracts the
edge of *its own* first-found answer set, so the four modes follow independent
trajectories and the reported number is total wall-clock over `numShots` shots.
(They agree only on shot 0 — the same cold full graph — then diverge: a warm
solver's VSIDS/phase locality keeps its next answer set clustered, while a cold
rebuild picks a fresh, scattered edge each shot.) The Java driver prints both
Alpha cut sequences and whether they stayed identical.

The four modes:

* **Alpha MSS** (`live`) — one long-lived `AlphaSession`; each shot calls
  `removeFacts` for the chosen edge. Retraction is handled **in place**: drop the
  retracted fact's unit nogood, clear the trail, discard learned nogoods (any
  might have been derived through the removed fact, so keeping them is unsound),
  and re-assert the surviving units — all on the **same live solver**, with the
  grounder, atom store, and VSIDS activity scores preserved across the cut.
* **Alpha rebuilt** (`batch`) — a fresh `AlphaSession` rebuilt each shot from
  `encoding + surviving edges`, solved from cold.
* **clingo rebuilt** — a fresh `clingo.Control` each shot, re-grounded from
  scratch; pays the O(V³) reachability grounding on **every** shot.
* **clingo MSS** — one long-lived `Control`; every edge is a ground
  `#external edge(a,b)` set true, grounded **once**, and each cut flips the
  chosen edge's external to false. The reachability recursion is grounded a
  single time at base setup.

## Running

Default — the full four-way sweep over the L&amp;W Table-2 instance set (10 shots,
180 s/mode wall-clock cap, clingo modes pruned forward once they time out):

```sh
./examples/cutedge/bench-cutedge-sweep.sh
# subset / knobs:
SHOTS=10 TIMEOUT=180 INSTANCES="100-30 100-50 200-30" ./examples/cutedge/bench-cutedge-sweep.sh
```

Single instance, Alpha live-vs-batch only (own sequences, prints the cut
sequences + a per-shot table):

```sh
./gradlew :alpha-cli-app:runIncrementalCutedgeRetractionBenchmark \
    --args="examples/cutedge/encoding.lp examples/cutedge/instances/edges-100-30.lp 10"
```

One clingo mode in isolation:

```sh
python3 examples/cutedge/clingo-cutedge.py \
    examples/cutedge/encoding.lp examples/cutedge/instances/edges-100-30.lp 10 mss   # or: rebuilt
```

## Results

Total wall-clock in seconds over 10 shots (Apple Silicon, OpenJDK 17, clingo
5.8.0, 180 s/mode). `MSS` = one long-lived control/session; `rebuilt` = fresh
each shot. Timeout = did not finish a mode's 10 shots within 180 s (clingo modes
pruned forward once timed out, since grounding cost is V-monotone).

| V/dens | Alpha MSS | Alpha rebuilt | clingo rebuilt | clingo MSS |
| -----: | --------: | ------------: | -------------: | ---------: |
| 100/30 |      1.17 |          1.48 |          55.45 |      20.31 |
| 100/50 |      3.04 |          3.00 |         158.66 |      63.85 |
| 200/30 |      6.62 |          8.48 |        Timeout |    Timeout |
| 200/50 |     15.90 |         18.40 |        Timeout |    Timeout |
| 300/10 |      3.78 |          5.93 |        Timeout |    Timeout |
| 300/30 |     16.91 |         21.07 |        Timeout |    Timeout |
| 500/10 |     12.51 |         18.36 |        Timeout |    Timeout |
| 500/30 |   Timeout |       Timeout |        Timeout |    Timeout |
| 500/50 |   Timeout |       Timeout |        Timeout |    Timeout |

Takeaways:

* **Lazy grounding dominates.** On the only sizes clingo survives (V=100), Alpha
  `live` beats clingo rebuilt **47–52×** and clingo MSS **17–21×**. Both clingo
  modes time out at V=200 — exactly the L&amp;W Table-2 grounding wall, here hit
  sooner because the 10-shot loop re-pays the O(V³) reachability grounding.
* **clingo MSS ≈ 2.5–2.7× faster than rebuilt** (one-time base grounding
  amortized over the 10 solves) but still explodes at V=200: that single base
  grounding already blows the budget.
* **Alpha `live` beats `batch` ≈ 1.2–1.6×** via warm grounder/store/VSIDS reuse
  across retractions; parity only at 100/50 (smallest, densest — per-solve search
  dominates the reuse). Alpha reaches 500/10 where all clingo modes are dead, and
  times out at 500/30–50, matching the paper's own Alpha timeouts there.

Only one random graph per size ships here (the paper averaged over 10), so treat
these as single-instance wall-clock, not averaged runtimes. See
[`examples/incremental.md`](../incremental.md) for the full retraction contract
(what state is retained vs. discarded).

### LaTeX

```latex
\begin{table}[t]
  \centering
  \small
  \caption{Cutedge iterative edge-cutting (retraction) loop, 10 shots. Total
    wall-clock in seconds; each mode drives its own cut sequence. MSS = one
    long-lived session/control; rebuilt = fresh each shot.}
  \label{tab:cutedge}
  \begin{tabular}{rrrrr}
    \toprule
    & \multicolumn{2}{c}{Alpha} & \multicolumn{2}{c}{clingo} \\
    \cmidrule(lr){2-3}
    \cmidrule(lr){4-5}
    $V/d$ & MSS & rebuilt & rebuilt & MSS \\
    \midrule
    100/30 & 1.17  & 1.48  & 55.45  & 20.31 \\
    100/50 & 3.04  & 3.00  & 158.66 & 63.85 \\
    200/30 & 6.62  & 8.48  & \multicolumn{2}{c}{Timeout} \\
    200/50 & 15.90 & 18.40 & \multicolumn{2}{c}{Timeout} \\
    300/10 & 3.78  & 5.93  & \multicolumn{2}{c}{Timeout} \\
    300/30 & 16.91 & 21.07 & \multicolumn{2}{c}{Timeout} \\
    500/10 & 12.51 & 18.36 & \multicolumn{2}{c}{Timeout} \\
    500/30 & \multicolumn{2}{c}{Timeout} & \multicolumn{2}{c}{Timeout} \\
    500/50 & \multicolumn{2}{c}{Timeout} & \multicolumn{2}{c}{Timeout} \\
    \bottomrule
  \end{tabular}
\end{table}
```
