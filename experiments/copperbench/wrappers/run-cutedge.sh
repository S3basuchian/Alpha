#!/usr/bin/env bash
# Cutedge (edge-retraction reachability) copperbench wrapper — Table 2.
#
#   bash run-cutedge.sh <config> <V> <pct>
#     <config> : alpha-mss | alpha-rebuilt | clingo-rebuilt | clingo-mss
#     <V>      : number of vertices    <pct> : average % of edges present
#
# 10 incremental shots, 1 JIT warm-up (matches examples/cutedge/bench-cutedge-sweep.sh).
# Instance graph is generated deterministically (seed 42) by gen_cutedge.py.
# The Alpha driver computes both live and batch in one JVM; the alpha-mss / alpha-rebuilt
# configs each run it and report only their own column.
set -u
source "$(cd "$(dirname "$0")" && pwd)/_common.sh"

CONFIG="${1:?config token required}"
V="${2:?vertex count required}"
PCT="${3:?edge percent required}"
announce cutedge "$V-$PCT"

CE="$EXAMPLES/cutedge"
ENCODING="$CE/encoding.lp"
EDGES="$CE/instances/edges-$V-$PCT.lp"
SHOTS="${SHOTS:-10}"
WARMUPS="${WARMUPS:-1}"
SEED="${SEED:-42}"
MAIN="IncrementalCutedgeRetractionBenchmark"

[[ -f "$EDGES" ]] || { mkdir -p "$CE/instances"; "$PYTHON" "$CE/gen_cutedge.py" "$V" "$PCT" "$SEED"; }

case "$CONFIG" in
  alpha-mss|alpha-rebuilt)
    label="incremental"; [[ "$CONFIG" == alpha-rebuilt ]] && label="batch"
    run_java "$MAIN" "$ENCODING" "$EDGES" "$SHOTS" "$WARMUPS"
    secs="$(grab_seconds "total alpha time \\($label\\):" "$JAVA_LOG")"   # one JVM prints both modes
    [[ -n "$secs" ]] && emit "$secs"
    ;;

  clingo-rebuilt)
    LOG="$(mktemp)"; trap 'rm -f "$LOG"' EXIT
    "$PYTHON" "$CE/clingo-cutedge.py" "$ENCODING" "$EDGES" "$SHOTS" rebuilt 2>&1 | tee "$LOG"
    secs="$(grab_seconds 'total clingo rebuilt time:' "$LOG")"
    [[ -n "$secs" ]] && emit "$secs"
    ;;

  clingo-mss)
    LOG="$(mktemp)"; trap 'rm -f "$LOG"' EXIT
    "$PYTHON" "$CE/clingo-cutedge.py" "$ENCODING" "$EDGES" "$SHOTS" mss 2>&1 | tee "$LOG"
    secs="$(grab_seconds 'total clingo MSS wall time \(incl. base setup\):' "$LOG")"
    [[ -n "$secs" ]] && emit "$secs"
    ;;

  *) echo "unknown config: $CONFIG" >&2; exit 2 ;;
esac
