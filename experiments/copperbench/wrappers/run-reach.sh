#!/usr/bin/env bash
# Reachability (edge-streaming from empty) copperbench wrapper — Table 3.
#
#   bash run-reach.sh <config> <V> <Emult> <seed>
#     <config> : alpha-mss | alpha-rebuilt | clingo-rebuilt | clingo-mss
#     <V>      : vertices    <Emult> : edge multiplier (|E| = V * Emult)   <seed> : sample seed
#
# 5 equally-sized shots (paper Table 3), single-source reachable/1 encoding. The full edge
# set is streamed from an initially empty graph. Random graph is generated deterministically
# from the per-sample seed (4th arg) by gen-random-graph.py.
set -u
source "$(cd "$(dirname "$0")" && pwd)/_common.sh"

CONFIG="${1:?config token required}"
V="${2:?vertex count required}"
EMULT="${3:?edge multiplier required}"
SEED="${4:?sample seed required}"
E=$(( V * EMULT ))
announce reach "$V-$EMULT-s$SEED"

RE="$EXAMPLES/reach"
ENCODING="$RE/encoding.lp"
EDGES="$RE/instances/edges-rand-v$V-e$E-s$SEED.lp"
SHOTS="${SHOTS:-5}"
CAP="${MSS_CAP:-4000000}"          # clingo-MSS viability cap: |V|^2 <= CAP (10000-node -> not viable)
MAIN="IncrementalReachBenchmark"

[[ -f "$EDGES" ]] || { mkdir -p "$RE/instances"; "$PYTHON" "$RE/gen-random-graph.py" --vertices "$V" --edges "$E" --seed "$SEED" > "$EDGES"; }

case "$CONFIG" in
  alpha-mss|alpha-rebuilt)
    mode=live; [[ "$CONFIG" == alpha-rebuilt ]] && mode=batch
    run_java "$MAIN" "$ENCODING" "$EDGES" "$SHOTS" "$mode"
    secs="$(grab_seconds "total alpha time" "$JAVA_LOG")"   # single mode per run: unambiguous
    [[ -n "$secs" ]] && emit "$secs"
    ;;

  clingo-rebuilt)
    # clingo re-invoked from scratch each shot on the cumulative edge set (5 growing chunks).
    TMP="$(mktemp)"; trap 'rm -f "$TMP"' EXIT
    total_lines=$(grep -c '^edge(' "$EDGES"); base=$(( total_lines / SHOTS )); rem=$(( total_lines % SHOTS ))
    off=0; total=0
    : > "$TMP"
    for ((s=1; s<=SHOTS; s++)); do
        size=$base; (( s <= rem )) && size=$(( base + 1 ))
        grep '^edge(' "$EDGES" | sed -n "$((off + 1)),$((off + size))p" >> "$TMP"; off=$(( off + size ))
        t0=$("$PYTHON" -c 'import time;print(time.time())')
        "$CLINGO" --quiet=2,2 --number=1 "$ENCODING" "$TMP" >/dev/null 2>&1
        rc=$?; [[ "$rc" != 10 && "$rc" != 20 && "$rc" != 30 ]] && { echo "clingo rc=$rc (killed / failed)" >&2; exit 1; }
        t1=$("$PYTHON" -c 'import time;print(time.time())')
        total=$("$PYTHON" -c "print(f'{$total + ($t1 - $t0):.3f}')")
    done
    echo "total clingo rebuilt time: ${total}s over $SHOTS shots"
    emit "$total"
    ;;

  clingo-mss)
    LOG="$(mktemp)"; trap 'rm -f "$LOG"' EXIT
    "$PYTHON" "$RE/clingo-multishot.py" "$ENCODING" "$EDGES" "$SHOTS" "$CAP" 2>&1 | tee "$LOG"
    if grep -q 'not viable' "$LOG"; then
        echo "clingo MSS not viable (|V|^2 > $CAP) -> Memout" >&2; exit 0   # no RESULT line: postprocess -> Memout
    fi
    secs="$(grab_seconds 'total clingo MSS wall time \(incl. base setup\):' "$LOG")"
    [[ -n "$secs" ]] && emit "$secs"
    ;;

  *) echo "unknown config: $CONFIG" >&2; exit 2 ;;
esac
