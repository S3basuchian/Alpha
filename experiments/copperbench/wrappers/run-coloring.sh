#!/usr/bin/env bash
# Graph 5-colouring (rotating mixed edits) copperbench wrapper — Table 4.
#
#   bash run-coloring.sh <config> <V> <E> <seed>
#     <config> : alpha-mss | alpha-rebuilt | clingo-rebuilt | clingo-mss
#     <V> <E>  : vertices / edges of the random base graph (|E| = 4|V|)    <seed> : sample seed
#
# 20 shots, per-sample seed (4th arg), rotation = mix (grow / add-edge / retract / constrain). The Alpha driver
# generates the base graph internally (JavaRandom) and records the model-dependent edit stream;
# clingo-rebuilt solves each shot's full program dump from scratch, clingo-mss replays the same
# per-shot edits incrementally against one long-lived clingo Control.
set -u
source "$(cd "$(dirname "$0")" && pwd)/_common.sh"

CONFIG="${1:?config token required}"
V="${2:?vertex count required}"
E="${3:?edge count required}"
SEED="${4:?sample seed required}"
announce coloring "$V-$E-s$SEED"

CO="$EXAMPLES/coloring"
SHOTS="${SHOTS:-20}"
ROTATION="${ROTATION:-mix}"
MAIN="IncrementalColoringBenchmark"

case "$CONFIG" in
  alpha-mss|alpha-rebuilt)
    label="live"; [[ "$CONFIG" == alpha-rebuilt ]] && label="batch"
    JVM_EXTRA="-Dcoloring.rotation=$ROTATION" run_java "$MAIN" "$V" "$E" "$SHOTS" "$SEED"
    secs="$(grab_seconds "total $label:" "$JAVA_LOG")"
    [[ -n "$secs" ]] && emit "$secs"
    ;;

  clingo-rebuilt)
    # Run Alpha once to dump each shot's full program, then solve each dump from scratch with clingo.
    DUMP="$(mktemp -d)"; CLOG="$(mktemp)"; trap 'rm -rf "$DUMP" "$CLOG"' EXIT
    JVM_EXTRA="-Dcoloring.rotation=$ROTATION -DclingoDumpDir=$DUMP" run_java "$MAIN" "$V" "$E" "$SHOTS" "$SEED"
    [[ -d "$DUMP" ]] || { echo "no clingo dump produced" >&2; exit 1; }
    "$PYTHON" "$CO/clingo-coloring.py" "$DUMP" 2>&1 | tee "$CLOG"
    secs="$(grab_seconds 'total clingo rebuilt time:' "$CLOG")"
    [[ -n "$secs" ]] && emit "$secs"
    ;;

  clingo-mss)
    # Run Alpha once to dump each shot's program, then replay the per-shot edits incrementally.
    DUMP="$(mktemp -d)"; MLOG="$(mktemp)"; trap 'rm -rf "$DUMP" "$MLOG"' EXIT
    JVM_EXTRA="-Dcoloring.rotation=$ROTATION -DclingoDumpDir=$DUMP" run_java "$MAIN" "$V" "$E" "$SHOTS" "$SEED"
    [[ -d "$DUMP" ]] || { echo "no clingo dump produced" >&2; exit 1; }
    "$PYTHON" "$CO/clingo-coloring-mss.py" "$DUMP" 2>&1 | tee "$MLOG"
    secs="$(grab_seconds 'total clingo MSS wall time \(incl. base setup\):' "$MLOG")"
    [[ -n "$secs" ]] && emit "$secs"
    ;;

  *) echo "unknown config: $CONFIG" >&2; exit 2 ;;
esac
