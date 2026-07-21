#!/usr/bin/env bash
# Incremental graph 5-colouring — MONOTONE GROWTH sweep (rotation=grow), four-way.
#
# Companion to bench-coloring-sweep.sh (the mixed-edit "Table 4" colourability
# test, which stays). That test stresses SEARCH under a model-invalidating edit
# stream; this one isolates the incremental machinery's GROUNDING / atom-store
# reuse under a purely monotone edit stream.
#
# Protocol (per config): the driver solves a random degree-8 base graph
# (|E| = EDGE_MULT*|V|, default 4*|V|, i.e. below the 5-colouring phase
# transition -> easy search), then each shot adds one pendant vertex + one edge
# (rotation=grow): a monotone, never-invalidating edit. Four solvers on the
# IDENTICAL grow sequence (the Java driver dumps every replayed shot via
# -DclingoDumpDir so clingo solves the exact same states):
#
#   Alpha MSS      live AlphaSession; grounder/atom-store/learned-nogoods warm
#                  across shots, re-solve warm each shot.
#   Alpha Rebuilt  fresh session each shot (re-grounds the whole program).
#   clingo Rebuilt fresh clingo.Control per dumped shot (grounds from scratch).
#   clingo MSS     one long-lived clingo Control; vertices grounded once, edges
#                  as #external — incremental grounding (clingo-coloring-mss.py).
#
# Because the instances are easy to colour, the per-shot cost is dominated by
# BUILDING the ground program, which Alpha MSS / clingo MSS amortise while the
# two Rebuilt baselines re-pay it every shot. Only the solve-first call is timed;
# reported numbers are overall runtime in seconds over the shot count.
#
# Usage:
#   ./bench-coloring-grow-sweep.sh
#   SIZES="1000 2000 4000" SHOTS_LIST="10 20 40" SEED=42 ./bench-coloring-grow-sweep.sh
#   SIZES="1000" SHOTS_LIST="10 20" ./bench-coloring-grow-sweep.sh          # quick subset
#   RUN_CLINGO=0 ./bench-coloring-grow-sweep.sh                             # Alpha-only
#   EDGE_MULT=4 TIMEOUT=120 ./bench-coloring-grow-sweep.sh                  # tunables
#
# NOTE: each (size, shots) is an independent run, so the 40-shot cells re-do the
# 10/20-shot work; the |V|=4000, 40-shot cell dominates wall-clock (~40 cold
# 4000-vertex Alpha-Rebuilt solves). Trim SIZES/SHOTS_LIST for a faster pass.
#
# Prerequisite: ./gradlew :alpha-cli-app:installDist   (the sweep runs the built jars directly)

set -u
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$ROOT/../.." && pwd)"
CP="$REPO_ROOT/alpha-cli-app/build/install/alpha-cli-app/lib/*"
MAIN="at.ac.tuwien.kr.alpha.app.examples.IncrementalColoringBenchmark"

SIZES="${SIZES:-1000 2000 4000}"
SHOTS_LIST="${SHOTS_LIST:-10 20 40}"
SEED="${SEED:-42}"
TIMEOUT="${TIMEOUT:-120}"
EDGE_MULT="${EDGE_MULT:-4}"   # |E| = EDGE_MULT * |V|  (default degree-8)
RUN_CLINGO="${RUN_CLINGO:-1}"
CLINGO_PY="${CLINGO_PY:-python3}"

have_clingo=0
if [[ "$RUN_CLINGO" == 1 ]]; then
    "$CLINGO_PY" -c 'import clingo' 2>/dev/null && have_clingo=1 || \
        echo "warn: python clingo not importable — clingo columns will be n/a" >&2
fi

LOGDIR="$(mktemp -d)"
echo "logs: $LOGDIR" >&2

# First float on the line matching the given label (times are the only decimals printed).
grab() { grep -E "$1" "$2" 2>/dev/null | grep -oE '[0-9]+\.[0-9]+' | head -1; }

echo
echo "==== coloring MONOTONE GROWTH sweep (rotation=grow, degree-$((2*EDGE_MULT)), seed=$SEED) ===="
printf '%-12s | %-5s | %-10s | %-12s | %-13s | %-10s\n' \
    "V/E" "shots" "Alpha MSS" "Alpha Rebuilt" "clingo Rebuilt" "clingo MSS"
printf '%-12s-+-%-5s-+-%-10s-+-%-12s-+-%-13s-+-%-10s\n' \
    "------------" "-----" "----------" "------------" "-------------" "----------"
for V in $SIZES; do
    E=$((V * EDGE_MULT))
    for S in $SHOTS_LIST; do
        alog="$LOGDIR/alpha-$V-$S.log"
        dump="$LOGDIR/dump-$V-$S"
        dumpflag=()
        [[ "$have_clingo" == 1 ]] && dumpflag=(-DclingoDumpDir="$dump")
        java -Xmx6g -Dcoloring.rotation=grow ${dumpflag[@]+"${dumpflag[@]}"} -cp "$CP" "$MAIN" "$V" "$E" "$S" "$SEED" "$TIMEOUT" > "$alog" 2>&1
        mss="$(grab 'total live:' "$alog")";  mss="${mss:-err}"
        reb="$(grab 'total batch:' "$alog")"; reb="${reb:-err}"

        creb="n/a"; cmss="n/a"
        if [[ "$have_clingo" == 1 && -d "$dump" ]]; then
            clog="$LOGDIR/clingo-reb-$V-$S.log"; mlog="$LOGDIR/clingo-mss-$V-$S.log"
            "$CLINGO_PY" "$ROOT/clingo-coloring.py"     "$dump" > "$clog" 2>&1
            "$CLINGO_PY" "$ROOT/clingo-coloring-mss.py" "$dump" > "$mlog" 2>&1
            c="$(grab 'total clingo rebuilt time:' "$clog")";        [[ -n "$c" ]] && creb="$c"
            m="$(grab 'total clingo MSS wall time' "$mlog")";        [[ -n "$m" ]] && cmss="$m"
        fi

        printf '%-12s | %-5s | %-10s | %-12s | %-13s | %-10s\n' "$V/$E" "$S" "$mss" "$reb" "$creb" "$cmss"
    done
done
echo
echo "  rotation=grow: monotone pendant-vertex growth (never invalidates the colouring)."
echo "  Alpha MSS / clingo MSS reuse state across shots; both Rebuilt baselines re-build each shot."
echo "  full per-run logs: $LOGDIR"
