#!/usr/bin/env bash
# Incremental ground-explosion (constraint-streaming) sweep.
#
# DEFAULT MODE (SWEEP_MODE=decreasing): the tuned per-size *decreasing* (front-loaded)
# forbid schedules under SCATTER order that make the live (incremental) AlphaSession beat
# batch (rebuild-each-shot) across the whole size range. One row per instance size; Alpha
# live-vs-batch only (per-shot schedules aren't supported by the clingo drivers, and clingo
# memouts for |dom|>=18 anyway).
#
#   8..20 -> 4,2,1             (forbid 7)
#   500   -> 256,128,64,32,16  (5/256, leaves 4)
#   1000  -> 512,256,128,64,32 (5/512, leaves 8)
#
# Rule (paper Table 1): X/Y = X shots, first forbids Y = nearest power of 2 to |dom|/2, each
# subsequent shot half the previous bound, leaving a small tail (~4-8). Forbidding down
# aggressively makes the later shots collapse (few surviving answer sets), so the live session's
# warm re-solve beats batch's cold rebuild. NB: an earlier 500 -> 128,64,32,16,8 (leaves 252)
# INVERTED this — the large leftover kept every shot enumerating 10 answer sets from a big
# surviving set, and live dragged that state, so live was SLOWER than batch. Sizes outside the
# tuned set fall back to a front-loaded halving heuristic (a warning is printed).
#
# LEGACY MODE (SWEEP_MODE=fourway): the original L&W-2018-Table-1 four-way comparison
# (Alpha live/batch + clingo rebuilt/MSS), uniform forbid, ~LEAVE_PCT% left, asc order.
#
# Usage:
#   ./bench-constraints-sweep.sh                     # default: decreasing/scatter, sizes 8..1000
#   ./bench-constraints-sweep.sh 8 12 500            # custom sizes (decreasing mode)
#   FORBIDORDER=asc ./bench-constraints-sweep.sh     # decreasing schedules, ascending order
#   SWEEP_MODE=fourway ./bench-constraints-sweep.sh  # original four-way L&W sweep

set -u
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

SWEEP_MODE="${SWEEP_MODE:-decreasing}"
MAXMODELS="${MAXMODELS:-10}"

# Legacy four-way knobs (only used by SWEEP_MODE=fourway).
SHOTS="${SHOTS:-5}"
CLINGO_MAX_N="${CLINGO_MAX_N:-16}"
CLINGO_TIMEOUT="${CLINGO_TIMEOUT:-90}"
LEAVE_PCT="${LEAVE_PCT:-10}"

if [[ $# -gt 0 ]]; then SIZES=("$@"); else SIZES=(8 10 12 14 16 18 20 500 1000); fi

# Per-size tuned decreasing schedule (front-loaded halving). Domain-dependent by design; see header.
schedule_for() {
    local n=$1
    case "$n" in
        8|10|12|14|16|18|20) echo "4,2,1" ;;
        500)  echo "256,128,64,32,16" ;;
        1000) echo "512,256,128,64,32" ;;
        *)
            echo "warn: dom-$n is not a tuned size; using front-loaded halving heuristic" >&2
            python3 - "$n" <<'PY'
import sys
N = int(sys.argv[1])
f = 1
while f * 2 <= max(1, N // 2):
    f *= 2
s, cum = [], 0
for _ in range(5):
    if f < 1:
        break
    take = min(f, (N - 1) - cum)   # never forbid the last element -> leftover >= 1
    if take <= 0:
        break
    s.append(take); cum += take; f //= 2
print(",".join(map(str, s)) if s else str(max(1, N - 1)))
PY
            ;;
    esac
}

# ------------------------------------------------------------------ default: decreasing schedules
if [[ "$SWEEP_MODE" == "decreasing" ]]; then
    FORBIDORDER="${FORBIDORDER:-scatter}"
    RESULT=()
    for N in "${SIZES[@]}"; do
        inst="dom-$N.lp"
        instpath="$ROOT/instances/$inst"
        [[ -f "$instpath" ]] || "$ROOT/gen_dom.py" "$N" > "$instpath"
        sched=$(schedule_for "$N")
        read -r T SHOTS_N < <(awk -F, '{s=0;for(i=1;i<=NF;i++)s+=$i;print s, NF+1}' <<< "$sched")
        # clingo (both modes) memouts on the |dom|^6 base grounding for |dom| >= 18; only run it below that.
        if (( N <= CLINGO_MAX_N )); then runc=1; else runc=0; fi

        echo "############################################################"
        echo "## |dom|=$N  schedule=$sched  leaves=$((N-T))  order=$FORBIDORDER  run_clingo=$runc"
        echo "############################################################"
        out=$(SHOTS="$SHOTS_N" FORBID="$sched" FORBIDTOTAL="$T" FORBIDORDER="$FORBIDORDER" \
              MAXMODELS="$MAXMODELS" RUN_CLINGO="$runc" CLINGO_TIMEOUT="$CLINGO_TIMEOUT" \
              "$ROOT/bench-constraints.sh" "$inst" 2>&1)
        echo "$out" | tail -14

        live=$(echo "$out"  | grep "incremental Alpha (live" | grep -oE '[0-9]+\.[0-9]+s' | head -1)
        batch=$(echo "$out" | grep "regular Alpha (rebuild"  | grep -oE '[0-9]+\.[0-9]+s' | head -1)
        if (( runc )); then
            crb=$(echo "$out" | grep "clingo (rebuilt each shot)"      | sed 's/.*):[[:space:]]*//')
            cms=$(echo "$out" | grep "clingo multi-shot (one Control)" | sed 's/.*):[[:space:]]*//')
        else
            crb="Memout"; cms="Memout"
        fi
        RESULT+=("$N|$sched|${live:-?}|${batch:-?}|${crb:-?}|${cms:-?}")
        echo
    done

    echo
    echo "========== SWEEP SUMMARY (decreasing schedules, order=$FORBIDORDER, n=$MAXMODELS) =========="
    printf "%-6s | %-18s | %-11s | %-11s | %-17s | %-13s\n" "|dom|" "schedule" "Alpha live" "Alpha batch" "clingo (rebuilt)" "clingo (MSS)"
    printf "%-6s-+-%-18s-+-%-11s-+-%-11s-+-%-17s-+-%-13s\n" "------" "------------------" "-----------" "-----------" "-----------------" "-------------"
    for row in "${RESULT[@]}"; do
        IFS='|' read -r n sc l b cr cm <<< "$row"
        printf "%-6s | %-18s | %-11s | %-11s | %-17s | %-13s\n" "$n" "$sc" "$l" "$b" "$cr" "$cm"
    done
    exit 0
fi

# ------------------------------------------------------------------ legacy: four-way L&W sweep
RESULT=()
for N in "${SIZES[@]}"; do
    inst="dom-$N.lp"
    instpath="$ROOT/instances/$inst"
    [[ -f "$instpath" ]] || "$ROOT/gen_dom.py" "$N" > "$instpath"
    if (( N <= CLINGO_MAX_N )); then runc=1; else runc=0; fi

    echo "############################################################"
    echo "## |dom|=$N  shots=$SHOTS  forbid=auto  leave=${LEAVE_PCT}%  run_clingo=$runc"
    echo "############################################################"
    out=$(SHOTS="$SHOTS" FORBID=auto LEAVE_PCT="$LEAVE_PCT" MAXMODELS="$MAXMODELS" RUN_CLINGO="$runc" \
          CLINGO_TIMEOUT="$CLINGO_TIMEOUT" "$ROOT/bench-constraints.sh" "$inst" 2>&1)
    echo "$out" | tail -22

    live=$(echo "$out"  | grep "incremental Alpha (live" | grep -oE '[0-9]+\.[0-9]+s' | head -1)
    batch=$(echo "$out" | grep "regular Alpha (rebuild"  | grep -oE '[0-9]+\.[0-9]+s' | head -1)
    if (( runc )); then
        crb=$(echo "$out" | grep "clingo (rebuilt each shot)"   | sed 's/.*):[[:space:]]*//')
        cms=$(echo "$out" | grep "clingo multi-shot (one Control)" | sed 's/.*):[[:space:]]*//')
    else
        crb="Memout"; cms="Memout"
    fi
    RESULT+=("$N|${live:-?}|${batch:-?}|${crb:-?}|${cms:-?}")
    echo
done

echo
echo "================ SWEEP SUMMARY (constraint-streaming, shots=$SHOTS, n=$MAXMODELS, forbid=auto, leave=${LEAVE_PCT}%) ================"
printf "%-13s | %-11s | %-11s | %-17s | %-13s\n" "Instance size" "Alpha live" "Alpha batch" "clingo (rebuilt)" "clingo (MSS)"
printf "%-13s-+-%-11s-+-%-11s-+-%-17s-+-%-13s\n" "-------------" "-----------" "-----------" "-----------------" "-------------"
for row in "${RESULT[@]}"; do
    IFS='|' read -r n l b cr cm <<< "$row"
    printf "%-13s | %-11s | %-11s | %-17s | %-13s\n" "$n" "$l" "$b" "$cr" "$cm"
done
