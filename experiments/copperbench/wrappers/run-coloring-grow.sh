#!/usr/bin/env bash
# Graph 5-colouring, MONOTONE GROWTH (rotation=grow) copperbench wrapper.
#
#   bash run-coloring-grow.sh <config> <V> <E> <SHOTS> <seed>
#     <config>      : alpha-mss | alpha-rebuilt | clingo-rebuilt | clingo-mss
#     <V> <E>       : vertices / edges of the random base graph (|E| = 4|V|)
#     <SHOTS>       : number of incremental growth shots    <seed> : sample seed
#
# Companion to run-coloring.sh (the mixed-edit Table-4 test). Here rotation is forced to
# `grow`: every shot adds one pendant vertex + one edge to a random existing vertex — a
# monotone, never-invalidating edit. The base graph is easy to colour (degree-8, below the
# 5-colouring transition), so this isolates the incremental machinery's GROUNDING / atom-store
# reuse (Alpha MSS / clingo MSS build the ground program once and reuse it; the two Rebuilt
# baselines re-build every shot). The Alpha driver generates the base graph internally
# (JavaRandom, per-sample seed = 5th arg) and records the grow sequence, dumping each shot's full program so
# clingo solves the identical states.
set -u
source "$(cd "$(dirname "$0")" && pwd)/_common.sh"

CONFIG="${1:?config token required}"
V="${2:?vertex count required}"
E="${3:?edge count required}"
SHOTS="${4:?shot count required}"
SEED="${5:?sample seed required}"
announce coloring-grow "$V-$E-$SHOTS-s$SEED"

CO="$EXAMPLES/coloring"
MAIN="IncrementalColoringBenchmark"

case "$CONFIG" in
  alpha-mss|alpha-rebuilt)
    label="live"; [[ "$CONFIG" == alpha-rebuilt ]] && label="batch"
    JVM_EXTRA="-Dcoloring.rotation=grow" run_java "$MAIN" "$V" "$E" "$SHOTS" "$SEED"
    secs="$(grab_seconds "total $label:" "$JAVA_LOG")"
    [[ -n "$secs" ]] && emit "$secs"
    ;;

  clingo-rebuilt)
    # Run Alpha once to dump each shot's full program, then solve each dump from scratch with clingo.
    DUMP="$(mktemp -d)"; CLOG="$(mktemp)"; trap 'rm -rf "$DUMP" "$CLOG"' EXIT
    JVM_EXTRA="-Dcoloring.rotation=grow -DclingoDumpDir=$DUMP" run_java "$MAIN" "$V" "$E" "$SHOTS" "$SEED"
    [[ -d "$DUMP" ]] || { echo "no clingo dump produced" >&2; exit 1; }
    "$PYTHON" "$CO/clingo-coloring.py" "$DUMP" 2>&1 | tee "$CLOG"
    secs="$(grab_seconds 'total clingo rebuilt time:' "$CLOG")"
    [[ -n "$secs" ]] && emit "$secs"
    ;;

  clingo-mss)
    # Run Alpha once to dump each shot's program, then replay the per-shot growth incrementally.
    DUMP="$(mktemp -d)"; MLOG="$(mktemp)"; trap 'rm -rf "$DUMP" "$MLOG"' EXIT
    JVM_EXTRA="-Dcoloring.rotation=grow -DclingoDumpDir=$DUMP" run_java "$MAIN" "$V" "$E" "$SHOTS" "$SEED"
    [[ -d "$DUMP" ]] || { echo "no clingo dump produced" >&2; exit 1; }
    "$PYTHON" "$CO/clingo-coloring-mss.py" "$DUMP" 2>&1 | tee "$MLOG"
    secs="$(grab_seconds 'total clingo MSS wall time \(incl. base setup\):' "$MLOG")"
    [[ -n "$secs" ]] && emit "$secs"
    ;;

  *) echo "unknown config: $CONFIG" >&2; exit 2 ;;
esac
