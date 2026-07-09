#!/usr/bin/env bash
# Ground explosion, MODEL-DEPENDENT forbid-all (maxAS=2) — copperbench wrapper.
# Each shot: enumerate up to 2 answer sets, block ALL selected elements found (:- p(i,...,i).),
# re-solve. Own sequence per solver (like cutedge): every column drives its own forbid stream.
# copperbench passes, positionally:  $1 config   $2 domSize   $3 shots   $4 seed
set -u
source "$(cd "$(dirname "$0")" && pwd)/_common.sh"

MAXAS=2
CONFIG="${1:?config token required}"
N="${2:?domain size required}"
SHOTS="${3:?shot count required}"
SEED="${4:?sample seed required}"
announce groundexp "$N-$SHOTS-s$SEED"

GE="$EXAMPLES/groundexp"
ENCODING="$GE/encoding.lp"
DOM="$GE/instances/dom-$N.lp"
MAIN="IncrementalGroundExpModelForbidBenchmark"
[[ -f "$DOM" ]] || { mkdir -p "$GE/instances"; "$PYTHON" "$GE/gen_dom.py" "$N" > "$DOM"; }

# forbid-all is deterministic; the sample SEED is only a timing repetition (the instance is fixed).
case "$CONFIG" in
  alpha-mss|alpha-rebuilt)
    m=mss; label="total mss:"
    [[ "$CONFIG" == alpha-rebuilt ]] && { m=rebuilt; label="total rebuilt:"; }
    JVM_EXTRA="-Dge.forbidAll=true -Dge.mode=$m" run_java "$MAIN" "$ENCODING" "$DOM" "$SHOTS" "$MAXAS" "$SEED"
    secs="$(grab_seconds "$label" "$JAVA_LOG")"
    [[ -n "$secs" ]] && emit "$secs"
    ;;
  clingo-rebuilt)
    LOG="$(mktemp)"; trap 'rm -f "$LOG"' EXIT
    "$PYTHON" "$GE/clingo-model-forbid.py" "$ENCODING" "$DOM" "$SHOTS" "$MAXAS" rebuilt 2>&1 | tee "$LOG"
    secs="$(grab_seconds 'total clingo rebuilt time:' "$LOG")"
    [[ -n "$secs" ]] && emit "$secs"
    ;;
  clingo-mss)
    LOG="$(mktemp)"; trap 'rm -f "$LOG"' EXIT
    "$PYTHON" "$GE/clingo-model-forbid.py" "$ENCODING" "$DOM" "$SHOTS" "$MAXAS" mss 2>&1 | tee "$LOG"
    secs="$(grab_seconds 'total clingo MSS wall time \(incl. base setup\):' "$LOG")"
    [[ -n "$secs" ]] && emit "$secs"
    ;;
  *) echo "unknown config: $CONFIG" >&2; exit 2 ;;
esac
