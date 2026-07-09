#!/usr/bin/env bash
# Reachability (full base + ONE edge per shot) copperbench wrapper — Table 3.
#
#   bash run-reach.sh <config> <V> <Emult> <SHOTS> <seed>
#     <config> : alpha-mss | alpha-rebuilt | clingo-rebuilt | clingo-mss
#     <V>      : vertices    <Emult> : edge multiplier (|E| = V * Emult)
#     <SHOTS>  : number of single-edge growth shots        <seed> : sample seed
#
# Protocol: shot 1 solves a near-full base graph (all but the last SHOTS edges); each of the
# following SHOTS shots adds exactly ONE edge and re-solves. Single-source reachable/1 encoding.
# The incremental session (alpha-mss) pays a 1-edge delta per shot while every rebuild baseline
# (alpha-rebuilt / clingo-rebuilt / clingo-mss) re-grounds the whole graph each shot. Random graph
# is generated deterministically from the per-sample seed by gen-random-graph.py.
set -u
source "$(cd "$(dirname "$0")" && pwd)/_common.sh"

CONFIG="${1:?config token required}"
V="${2:?vertex count required}"
EMULT="${3:?edge multiplier required}"
SHOTS="${4:?shot count required}"
SEED="${5:?sample seed required}"
E=$(( V * EMULT ))
announce reach "$V-$EMULT-$SHOTS-s$SEED"

RE="$EXAMPLES/reach"
ENCODING="$RE/encoding.lp"
EDGES="$RE/instances/edges-rand-v$V-e$E-s$SEED.lp"
CAP="${MSS_CAP:-4000000}"          # clingo-MSS viability cap: |V|^2 <= CAP (10000-node -> not viable)
MAIN="IncrementalReachBenchmark"

[[ -f "$EDGES" ]] || { mkdir -p "$RE/instances"; "$PYTHON" "$RE/gen-random-graph.py" --vertices "$V" --edges "$E" --seed "$SEED" > "$EDGES"; }

case "$CONFIG" in
  alpha-mss|alpha-rebuilt)
    mode=live; [[ "$CONFIG" == alpha-rebuilt ]] && mode=batch
    JVM_EXTRA="-Dreach.oneEdgeShots=true" run_java "$MAIN" "$ENCODING" "$EDGES" "$SHOTS" "$mode"
    secs="$(grab_seconds "total alpha time" "$JAVA_LOG")"   # single mode per run: unambiguous
    [[ -n "$secs" ]] && emit "$secs"
    ;;

  clingo-rebuilt)
    # clingo re-invoked from scratch each shot: shot 1 = near-full base (E-SHOTS edges), then one
    # edge added per shot, re-solving the whole cumulative graph each time (SHOTS+1 full solves).
    ALL="$(mktemp)"; TMP="$(mktemp)"; trap 'rm -f "$ALL" "$TMP"' EXIT
    grep '^edge(' "$EDGES" > "$ALL"
    total_lines=$(wc -l < "$ALL"); base=$(( total_lines - SHOTS )); (( base < 0 )) && base=0
    : > "$TMP"; total=0
    solve_now() {
        local t0 t1 rc
        t0=$("$PYTHON" -c 'import time;print(time.time())')
        "$CLINGO" --quiet=2,2 --number=1 "$ENCODING" "$TMP" >/dev/null 2>&1; rc=$?
        [[ "$rc" != 10 && "$rc" != 20 && "$rc" != 30 ]] && { echo "clingo rc=$rc (killed / failed)" >&2; exit 1; }
        t1=$("$PYTHON" -c 'import time;print(time.time())')
        total=$("$PYTHON" -c "print(f'{$total + ($t1 - $t0):.3f}')")
    }
    sed -n "1,${base}p" "$ALL" >> "$TMP"; solve_now            # shot 1: base
    for ((i=1; i<=SHOTS; i++)); do
        sed -n "$((base + i))p" "$ALL" >> "$TMP"; solve_now     # +1 edge, full re-solve
    done
    echo "total clingo rebuilt time: ${total}s over $((SHOTS + 1)) shots"
    emit "$total"
    ;;

  clingo-mss)
    LOG="$(mktemp)"; trap 'rm -f "$LOG"' EXIT
    "$PYTHON" "$RE/clingo-multishot.py" --one-edge "$ENCODING" "$EDGES" "$SHOTS" "$CAP" 2>&1 | tee "$LOG"
    if grep -q 'not viable' "$LOG"; then
        echo "clingo MSS not viable (|V|^2 > $CAP) -> Memout" >&2; exit 0   # no RESULT line: postprocess -> Memout
    fi
    secs="$(grab_seconds 'total clingo MSS wall time \(incl. base setup\):' "$LOG")"
    [[ -n "$secs" ]] && emit "$secs"
    ;;

  *) echo "unknown config: $CONFIG" >&2; exit 2 ;;
esac
