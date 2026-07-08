#!/usr/bin/env bash
# Incremental graph 5-colouring sweep — reproduces the paper's Table 4.
#
# For each |V|/|E| instance (degree-8 random graphs, |E| = 4*|V|) the driver runs
# one live AlphaSession over a rotating mixed edit stream (grow / add-edge /
# retract / constrain) for SHOTS shots, then replays the IDENTICAL edit stream
# rebuilding a fresh session each shot. Both time only the solve-first call.
#
#   MSS (live)  incremental Alpha — one long-lived session; grounder/store/VSIDS
#               warm across shots (with a forced foundedness + VSIDS reset each
#               shot, per the paper). == paper Table 4 "Alpha MSS".
#   Rebuilt     from-scratch Alpha — fresh session rebuilt each shot from the
#               current v/e/constraint state.                == paper "Alpha Rebuilt".
#   clingo      rebuilt clingo — each replayed shot's full program is dumped
#               (-DclingoDumpDir) and solved from scratch by clingo.
#               == paper "clingo Rebuilt".  (set RUN_CLINGO=0 to skip.)
#
# Every mode solves the exact same program each shot (the live session records
# the model/RNG-dependent edit sequence; batch and clingo replay it), so the
# comparison is apples-to-apples.
#
# Usage:
#   ./bench-coloring-sweep.sh                       # all 8 paper instances, 20 shots, seed 42
#   SHOTS=20 SEED=42 ./bench-coloring-sweep.sh
#   RUN_CLINGO=0 ./bench-coloring-sweep.sh          # Alpha MSS-vs-Rebuilt only
#   INSTANCES="10/40 1000/4000" ./bench-coloring-sweep.sh   # subset
#
# Prerequisite: ./gradlew :alpha-cli-app:installDist   (the sweep runs the built jars directly)

set -u
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$ROOT/../.." && pwd)"
CP="$REPO_ROOT/alpha-cli-app/build/install/alpha-cli-app/lib/*"
MAIN="at.ac.tuwien.kr.alpha.app.examples.IncrementalColoringBenchmark"

SHOTS="${SHOTS:-20}"
SEED="${SEED:-42}"
RUN_CLINGO="${RUN_CLINGO:-1}"
CLINGO_PY="${CLINGO_PY:-python3}"
# paper Table 4 instance set (|V|/|E|, degree-8 → |E| = 4*|V|), ascending.
INSTANCES="${INSTANCES:-10/40 20/80 30/120 40/160 50/200 100/400 400/1600 1000/4000}"

have_clingo_py=0
if [[ "$RUN_CLINGO" == 1 ]]; then
    "$CLINGO_PY" -c 'import clingo' 2>/dev/null && have_clingo_py=1 || \
        echo "warn: python clingo not importable — clingo column will be n/a" >&2
fi

LOGDIR="$(mktemp -d)"
echo "logs: $LOGDIR" >&2

grab() { grep -oE "$1[^0-9]*[0-9]+\.[0-9]+s" "$2" 2>/dev/null | grep -oE '[0-9]+\.[0-9]+' | head -1; }

declare -a ROWS
for inst in $INSTANCES; do
    V="${inst%/*}"; E="${inst#*/}"
    echo "=== |V|=$V |E|=$E (seed=$SEED, $SHOTS shots) ===" >&2
    dump="$LOGDIR/dump-$V-$E"
    alog="$LOGDIR/alpha-$V-$E.log"

    dumpflag=()
    [[ "$have_clingo_py" == 1 ]] && dumpflag=(-DclingoDumpDir="$dump")
    # ${arr[@]+"${arr[@]}"} — bash-3.2-safe expansion of a possibly-empty array under `set -u`.
    java -XX:MaxRAM=8000M -Xmx3500M ${dumpflag[@]+"${dumpflag[@]}"} -cp "$CP" "$MAIN" "$V" "$E" "$SHOTS" "$SEED" > "$alog" 2>&1

    live="$(grab 'total live:'  "$alog")";  [[ -z "$live" ]]  && live="err" || live="${live}s"
    batch="$(grab 'total batch:' "$alog")"; [[ -z "$batch" ]] && batch="err" || batch="${batch}s"
    # grep -c prints "0" AND exits 1 on no match, so don't append a second count with `|| echo`.
    to="$(grep -c 'TIMEOUT' "$alog" 2>/dev/null)"; to="${to:-0}"

    clingo="n/a"
    if [[ "$have_clingo_py" == 1 && -d "$dump" ]]; then
        clog="$LOGDIR/clingo-$V-$E.log"
        "$CLINGO_PY" "$ROOT/clingo-coloring.py" "$dump" > "$clog" 2>&1
        c="$(grab 'total clingo rebuilt time:' "$clog")"
        [[ -n "$c" ]] && clingo="${c}s"
    fi

    note=""; [[ "$to" -gt 0 ]] && note="  (live timeouts: $to)"
    ROWS+=("$(printf '%-10s | %-11s | %-11s | %-13s%s' "$V/$E" "$live" "$batch" "$clingo" "$note")")
    echo "    MSS=$live  Rebuilt=$batch  clingo=$clingo" >&2
done

echo
echo "==== coloring sweep (rotating mix, $SHOTS shots, seed=$SEED) ===="
printf '%-10s | %-11s | %-11s | %-13s\n' "V/E" "Alpha MSS" "Alpha Rebuilt" "clingo (rebuilt)"
printf '%-10s-+-%-11s-+-%-11s-+-%-13s\n' "----------" "-----------" "-----------" "-------------"
for r in "${ROWS[@]}"; do echo "$r"; done
echo
echo "  Alpha MSS = live incremental session; Alpha Rebuilt = fresh session each shot; clingo = rebuilt each shot (from -DclingoDumpDir dumps)."
echo "  10/40 is UNSAT on every shot (near-complete graph): no warm state to reuse, so MSS ~= Rebuilt there."
echo "  full per-instance logs: $LOGDIR"
