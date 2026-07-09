#!/usr/bin/env bash
# Ground-explosion (constraint-streaming) copperbench wrapper — Table 1.
#
#   bash run-groundexp.sh <config> <domSize> <seed>
#     <config>  : alpha-mss | alpha-rebuilt | clingo-rebuilt | clingo-mss
#     <domSize> : |dom| (8 10 12 14 16 18 20 500 1000)   <seed> : sample seed (permutes the forbid order)
#
# Per-size forbid schedule (paper "X/Y" notation), order = scatter (per-sample seed, 3rd arg), n = 10 answer
# sets/shot. A single scatter order file is generated once and handed to every solver so all
# four forbid the identical element sequence (the soundness cross-check). Mirrors
# examples/groundexp/bench-constraints.sh exactly, but runs ONE config in isolation.
set -u
source "$(cd "$(dirname "$0")" && pwd)/_common.sh"

CONFIG="${1:?config token required}"
N="${2:?domain size required}"
SEED="${3:?sample seed required}"
announce groundexp "$N-s$SEED"

GE="$EXAMPLES/groundexp"
ENCODING="$GE/encoding.lp"
DOM="$GE/instances/dom-$N.lp"
MAXMODELS="${MAXMODELS:-10}"
MAIN="IncrementalGroundExplosionConstraintBenchmark"

[[ -f "$DOM" ]] || { mkdir -p "$GE/instances"; "$PYTHON" "$GE/gen_dom.py" "$N" > "$DOM"; }

# Per-size decreasing (front-loaded halving) forbid schedule — paper Table 1.
schedule_for() {
    case "$1" in
        8|10|12|14|16|18|20) echo "4,2,1" ;;
        500)  echo "256,128,64,32,16" ;;
        1000) echo "512,256,128,64,32" ;;
        *) echo "ERR"; return 1 ;;
    esac
}
SCHED="$(schedule_for "$N")" || { echo "no tuned schedule for dom=$N" >&2; exit 2; }
# SHOTS = (#schedule entries) + 1 base shot; FORBIDTOTAL = sum of entries.
read -r FORBIDTOTAL SHOTS < <(awk -F, '{s=0;for(i=1;i<=NF;i++)s+=$i;print s, NF+1}' <<< "$SCHED")

# Domain elements in file order, then the deterministic scatter permutation (python seed = this
# sample's SEED), one element per line — identical to bench-constraints.sh's scatter branch, and
# handed identically to all four solvers so their per-shot answer-set counts still cross-check.
mapfile_els() { grep -oE 'dom\([0-9]+\)' "$DOM" | grep -oE '[0-9]+'; }
ELS=(); while IFS= read -r e; do [[ -n "$e" ]] && ELS+=("$e"); done < <(mapfile_els)
ORDER="$(mktemp)"; trap 'rm -f "$ORDER"' EXIT
"$PYTHON" -c 'import random,sys; seed=int(sys.argv[1]); xs=sys.argv[2:]; random.Random(seed).shuffle(xs); print("\n".join(xs))' "$SEED" "${ELS[@]}" > "$ORDER"
ORD=(); while IFS= read -r e; do [[ -n "$e" ]] && ORD+=("$e"); done < "$ORDER"

case "$CONFIG" in
  alpha-mss|alpha-rebuilt)
    mode=live; [[ "$CONFIG" == alpha-rebuilt ]] && mode=batch
    run_java "$MAIN" "$ENCODING" "$DOM" "$SHOTS" "$mode" "$SCHED" "$MAXMODELS" scatter "$FORBIDTOTAL" "$ORDER"
    secs="$(grab_seconds "total alpha time" "$JAVA_LOG")"   # single mode per run: unambiguous
    [[ -n "$secs" ]] && emit "$secs"
    ;;

  clingo-rebuilt)
    # Fresh clingo each shot on encoding + full dom + cumulative constraints so far.
    # Cumulative forbidden count per shot: shot 1 forbids 0; shot s adds SCHED[s-2]; capped at FORBIDTOTAL.
    IFS=',' read -ra SA <<< "$SCHED"
    CONS="$(mktemp)"; trap 'rm -f "$ORDER" "$CONS"' EXIT
    total=0; cum=0
    for ((shot=1; shot<=SHOTS; shot++)); do
        if (( shot >= 2 )); then add=${SA[$((shot-2))]}; cum=$(( cum + add )); (( cum > FORBIDTOTAL )) && cum=$FORBIDTOTAL; fi
        : > "$CONS"
        for ((i=0; i<cum; i++)); do e=${ORD[$i]}; printf ':- p(%s,%s,%s,%s,%s,%s).\n' "$e" "$e" "$e" "$e" "$e" "$e" >> "$CONS"; done
        t0=$("$PYTHON" -c 'import time;print(time.time())')
        "$CLINGO" -n "$MAXMODELS" --quiet=2 "$ENCODING" "$DOM" "$CONS" >/dev/null 2>&1
        rc=$?; [[ "$rc" != 10 && "$rc" != 20 && "$rc" != 30 ]] && { echo "clingo rc=$rc (grounding failed / killed)" >&2; exit 1; }
        t1=$("$PYTHON" -c 'import time;print(time.time())')
        total=$("$PYTHON" -c "print(f'{$total + ($t1 - $t0):.3f}')")
    done
    echo "total clingo rebuilt time: ${total}s over $SHOTS shots"
    emit "$total"
    ;;

  clingo-mss)
    LOG="$(mktemp)"; trap 'rm -f "$ORDER" "$LOG"' EXIT
    "$PYTHON" "$GE/clingo-multishot-constraints.py" "$ENCODING" "$DOM" "$SHOTS" "$SCHED" "$MAXMODELS" "$FORBIDTOTAL" "$ORDER" 2>&1 | tee "$LOG"
    secs="$(grab_seconds 'total clingo MSS wall time \(incl. base setup\):' "$LOG")"
    [[ -n "$secs" ]] && emit "$secs"
    ;;

  *) echo "unknown config: $CONFIG" >&2; exit 2 ;;
esac
