#!/usr/bin/env bash
# Cutedge (edge-retraction reachability) copperbench wrapper — Table 2.
#
#   bash run-cutedge.sh <config> <V> <pct> <seed>
#     <config> : alpha-mss | alpha-rebuilt | clingo-rebuilt | clingo-mss
#     <V>      : number of vertices    <pct> : average % of edges present    <seed> : sample seed
#
# 10 incremental shots, 1 JIT warm-up (matches examples/cutedge/bench-cutedge-sweep.sh).
# Instance graph is generated deterministically from the per-sample seed (4th arg) by gen_cutedge.py.
# The Alpha driver computes both live and batch in one JVM; the alpha-mss / alpha-rebuilt
# configs each run it and report only their own column.
set -u
source "$(cd "$(dirname "$0")" && pwd)/_common.sh"

CONFIG="${1:?config token required}"
V="${2:?vertex count required}"
PCT="${3:?edge percent required}"
SEED="${4:?sample seed required}"
announce cutedge "$V-$PCT-s$SEED"

CE="$EXAMPLES/cutedge"
ENCODING="$CE/encoding.lp"
EDGES="$CE/instances/edges-$V-$PCT-s$SEED.lp"
SHOTS="${SHOTS:-10}"
WARMUPS="${WARMUPS:-1}"
MAIN="IncrementalCutedgeRetractionBenchmark"

# Per-sample random graph (seed = SEED). gen_cutedge.py writes the fixed-name file; rename to the
# seeded name. Each solver still drives its OWN answer-set cut sequence on this shared graph.
[[ -f "$EDGES" ]] || { mkdir -p "$CE/instances"; "$PYTHON" "$CE/gen_cutedge.py" "$V" "$PCT" "$SEED" >/dev/null; mv "$CE/instances/edges-$V-$PCT.lp" "$EDGES"; }

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
