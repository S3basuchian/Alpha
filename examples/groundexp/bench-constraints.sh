#!/usr/bin/env bash
# Ground Explosion (constraint-streaming variant): fixed dom/1 universe, stream
# forbidding constraints. Four-way comparison of the canonical multi-shot
# "solve, then block solutions and re-solve" loop:
#
#   live        incremental Alpha  — one long-lived AlphaSession; add a
#                                    constraint per shot, keep grounder + atom
#                                    store + learned nogoods + VSIDS warm.
#   batch       regular Alpha      — rebuild a fresh session each shot from
#                                    encoding + full dom + all constraints so far.
#   clingo      rebuilt clingo     — re-invoke the clingo binary from scratch each
#                                    shot on encoding + full dom + constraints so far.
#   clingo MSS  multi-shot clingo  — one long-lived Control; fixed dom grounded
#                                    once at base, each shot grounds only the new
#                                    constraints (clingo-multishot-constraints.py).
#
# Shot 1 is the unconstrained base (identical across all four; == non-incremental).
# Each later shot forbids the next FORBID domain elements via ground constraints
#   :- p(i,i,i,i,i,i).
# and re-solves for up to MAXMODELS (default 10) answer sets. The answer-set count
# column is min(available, MAXMODELS): it stays pinned at the cap while >MAXMODELS
# selections survive, then drops as constraints exhaust them. All four count columns
# must agree shot-for-shot — that doubles as a cross-solver soundness check.
#
# Both clingo modes pay the |dom|^6 grounding explosion on the fixed universe
# (rebuilt: once per shot; MSS: once at base setup), so they fail for |dom| >= 18.
#
# Usage:
#   ./bench-constraints.sh                          # default: dom-50, 11 shots, forbid 5/shot
#   ./bench-constraints.sh dom-12.lp 6 2            # instance, shots, forbid/shot
#   MAXMODELS=10 CLINGO_TIMEOUT=60 ./bench-constraints.sh dom-16.lp 5 2
#   RUN_CLINGO=0 ./bench-constraints.sh dom-50.lp 11 5   # Alpha live vs batch only

set -u

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$ROOT/../.." && pwd)"

ENCODING="$ROOT/encoding.lp"
CLINGO="${CLINGO:-clingo}"
MAXMODELS="${MAXMODELS:-10}"
CLINGO_TIMEOUT="${CLINGO_TIMEOUT:-60}"
RUN_CLINGO="${RUN_CLINGO:-1}"
FORBIDORDER="${FORBIDORDER:-asc}"
# With FORBID=auto, leave ~LEAVE_PCT% of the domain unconstrained after the last shot (forbid the rest,
# spread across the constraint shots). LEAVE_PCT=0 recovers the old "forbid the whole domain" behaviour.
LEAVE_PCT="${LEAVE_PCT:-10}"

INSTANCE="${1:-dom-50.lp}"
SHOTS="${SHOTS:-${2:-11}}"
# FORBID may be a number or "auto". "auto" scales forbidPerShot with |dom| so the (SHOTS-1) constraint
# shots forbid (approximately) the domain minus a LEAVE_PCT% tail: forbidTotal = round((1-LEAVE_PCT/100)*|dom|)
# elements are forbidden, spread ceil(forbidTotal/(SHOTS-1)) per shot, leaving ~LEAVE_PCT% unconstrained.
# This keeps the shot count constant across instance sizes and scales both the streaming rate and the
# forbidden fraction with the universe. A numeric FORBID leaves forbidTotal at |dom| (no extra cap).
FORBID="${FORBID:-${3:-5}}"

# FORBID is either a single integer (uniform forbidPerShot) or a comma-separated per-shot schedule
# ("250,150,50" — front-loaded/decayed). Both are understood by every solver in the suite (Alpha and
# the two clingo drivers), so all four stay comparable and their answer-set counts must still agree.

INSTPATH="$ROOT/instances/$INSTANCE"
if [[ ! -f "$INSTPATH" ]]; then
    echo "Instance not found: $INSTPATH" >&2
    exit 1
fi

# Domain elements in file order (for auto-forbid and per-shot constraint files).
# (while-read, not mapfile — macOS ships bash 3.2.)
ELS=()
while IFS= read -r e; do
    [[ -n "$e" ]] && ELS+=("$e")
done < <(grep -oE 'dom\([0-9]+\)' "$INSTPATH" | grep -oE '[0-9]+')
NEL=${#ELS[@]}

# FORBIDTOTAL caps the distinct elements ever forbidden across the run. Default: the whole domain
# (no extra cap → a numeric FORBID behaves exactly as before). FORBID=auto overrides it below to leave
# a ~LEAVE_PCT% tail unconstrained. All four solvers are handed this same cap so their per-shot
# answer-set counts stay identical (the soundness cross-check).
FORBIDTOTAL="${FORBIDTOTAL:-$NEL}"

if [[ "$FORBID" == "auto" ]]; then
    denom=$(( SHOTS > 1 ? SHOTS - 1 : 1 ))
    LEAVE=$(( (NEL * LEAVE_PCT + 50) / 100 ))   # round(|dom| * LEAVE_PCT / 100)
    (( LEAVE_PCT > 0 && LEAVE < 1 )) && LEAVE=1  # always leave at least one element when LEAVE_PCT>0
    (( LEAVE > NEL )) && LEAVE=$NEL
    FORBIDTOTAL=$(( NEL - LEAVE ))
    (( FORBIDTOTAL < 0 )) && FORBIDTOTAL=0
    FORBID=$(( (FORBIDTOTAL + denom - 1) / denom ))   # ceil(forbidTotal / (SHOTS-1))
    (( FORBID < 1 )) && FORBID=1
    echo "auto: leave ${LEAVE_PCT}% (=$LEAVE of $NEL) unconstrained -> forbidTotal=$FORBIDTOTAL, forbidPerShot=ceil($FORBIDTOTAL/(shots-1=$denom))=$FORBID"
fi

# Run a command with a hard wall-clock kill (TERM, then KILL). Returns 124 on
# timeout, otherwise the command's own exit code. stdout/stderr pass through.
with_timeout() {
    local secs="$1"; shift
    perl -e '
        my $secs = shift @ARGV;
        my $pid = fork();
        if ($pid == 0) { exec @ARGV or die "exec: $!\n"; }
        my $timed_out = 0;
        eval {
            local $SIG{ALRM} = sub { kill "TERM", $pid; sleep 1; kill "KILL", $pid; $timed_out = 1; die "to\n"; };
            alarm($secs);
            waitpid($pid, 0);
            alarm(0);
        };
        exit($timed_out ? 124 : ($? >> 8));
    ' "$secs" "$@"
}

LIVE_OUT="$(mktemp)"
BATCH_OUT="$(mktemp)"
MSS_OUT="$(mktemp)"
CONS="$(mktemp)"
ORDER="$(mktemp)"
trap 'rm -f "$LIVE_OUT" "$BATCH_OUT" "$MSS_OUT" "$CONS" "$ORDER"' EXIT

# Single source of truth for the forbidding order: the ordered element sequence (one per line) that
# every solver consumes, so all four forbid the identical set even under desc/scatter. Computed once
# here rather than reimplementing the same permutation in bash, Java and Python. The first FORBIDTOTAL
# entries are forbidden; the remainder is the unconstrained tail.
case "$FORBIDORDER" in
    asc)
        printf '%s\n' "${ELS[@]}" > "$ORDER" ;;
    desc)
        for (( i=NEL-1; i>=0; i-- )); do printf '%s\n' "${ELS[$i]}"; done > "$ORDER" ;;
    scatter)
        python3 -c 'import random, sys; xs = sys.argv[1:]; random.Random(42).shuffle(xs); print("\n".join(xs))' "${ELS[@]}" > "$ORDER" ;;
    *)
        echo "Unknown FORBIDORDER: $FORBIDORDER (use asc|desc|scatter)" >&2; exit 2 ;;
esac
# Load it back for the shell's own (rebuilt-clingo) constraint generation.
ORD=()
while IFS= read -r e; do
    [[ -n "$e" ]] && ORD+=("$e")
done < "$ORDER"

run_alpha() {
    local mode="$1" out="$2"
    (cd "$REPO_ROOT" && ./gradlew --quiet :alpha-cli-app:runIncrementalGroundExplosionConstraintBenchmark \
            --args="$ENCODING $INSTPATH $SHOTS $mode $FORBID $MAXMODELS $FORBIDORDER $FORBIDTOTAL $ORDER" 2>/dev/null) | tee "$out"
}

# 1. Alpha live
echo
echo "==== incremental Alpha (live session, +$FORBID constraint/shot, $SHOTS shots, n=$MAXMODELS) ===="
run_alpha live "$LIVE_OUT"

# 2. Alpha batch
echo
echo "==== regular Alpha (batch: rebuild from scratch each shot, $SHOTS shots, n=$MAXMODELS) ===="
run_alpha batch "$BATCH_OUT"

clingo_total=""
clingo_counts=""
mss_total="TIMEOUT"
mss_counts=""

if [[ "$RUN_CLINGO" == "1" ]]; then
    if ! command -v "$CLINGO" >/dev/null 2>&1; then
        echo; echo "clingo not found in PATH — skipping both clingo modes." >&2
    else
        # 3. rebuilt clingo: fresh process each shot, encoding + full dom + constraints so far.
        echo
        echo "==== clingo (rebuilt from scratch each shot, $SHOTS shots, n=$MAXMODELS, ${CLINGO_TIMEOUT}s/shot) ===="
        printf "%-6s | %-9s | %-12s | %-12s | %-14s\n" "shot" "constr +" "constr total" "answer sets" "clingo (s)"
        printf "%-6s-+-%-9s-+-%-12s-+-%-12s-+-%-14s\n" "------" "---------" "------------" "------------" "--------------"
        # Per-shot cumulative-forbidden counts (CUM[shot]); handles a scalar FORBID or a comma schedule.
        # Shot 1 forbids nothing; shot s (>=2) adds FORBID, or the schedule's (s-2)-th entry. Capped at
        # FORBIDTOTAL so the domain tail stays unconstrained, matching the Alpha driver's forbidTotal.
        CUM=(0 0)   # CUM[1]=0 (shot 1 forbids nothing)
        [[ "$FORBID" == *,* ]] && IFS=',' read -ra SCHED_ARR <<< "$FORBID"
        for ((s=2; s<=SHOTS; s++)); do
            if [[ "$FORBID" == *,* ]]; then
                idx=$((s-2)); add=0; (( idx < ${#SCHED_ARR[@]} )) && add=${SCHED_ARR[$idx]}
                c=$(( CUM[s-1] + add ))
            else
                c=$(( (s-1) * FORBID ))
            fi
            (( c > FORBIDTOTAL )) && c=$FORBIDTOTAL
            CUM[$s]=$c
        done
        ct=0; cfailed=0; counts=()
        for ((shot=1; shot<=SHOTS; shot++)); do
            cum=${CUM[$shot]}
            prev=0; (( shot > 1 )) && prev=${CUM[$((shot-1))]}
            added=$(( cum - prev ))
            : > "$CONS"
            for ((i=0; i<cum; i++)); do
                e=${ORD[$i]}
                printf ':- p(%s,%s,%s,%s,%s,%s).\n' "$e" "$e" "$e" "$e" "$e" "$e" >> "$CONS"
            done
            t0=$(python3 -c 'import time; print(time.time())')
            with_timeout "$CLINGO_TIMEOUT" "$CLINGO" -n "$MAXMODELS" --quiet=2 "$ENCODING" "$INSTPATH" "$CONS" > "$MSS_OUT" 2>&1
            rc=$?
            t1=$(python3 -c 'import time; print(time.time())')
            el=$(python3 -c "print(f'{$t1 - $t0:.3f}')")
            if [[ "$rc" == "10" || "$rc" == "20" || "$rc" == "30" ]]; then
                cnt=$(grep -oE 'Models[[:space:]]*:[[:space:]]*[0-9]+\+?' "$MSS_OUT" | grep -oE '[0-9]+' | head -1)
                printf "%-6d | %-9d | %-12d | %-12s | %14.3f\n" "$shot" "$added" "$cum" "${cnt:-?}" "$el"
                counts+=("${cnt:-?}")
                ct=$(python3 -c "print(f'{$ct + $el:.3f}')")
            else
                msg="TIMEOUT"; [[ "$rc" != "124" ]] && msg="ERR(rc=$rc)"
                printf "%-6d | %-9d | %-12d | %-12s | %14s\n" "$shot" "$added" "$cum" "-" "$msg"
                cfailed=1; break
            fi
        done
        echo
        if (( cfailed )); then
            echo "  total clingo time: FAILED (base |dom|^6 grounding did not finish within ${CLINGO_TIMEOUT}s/shot)"
        else
            echo "  total clingo time: ${ct}s over $SHOTS shots"
            clingo_total="$ct"
            clingo_counts=$(IFS=,; echo "${counts[*]}")
        fi

        # 4. multi-shot clingo: one Control, fixed dom grounded once, stream constraints.
        echo
        echo "==== clingo multi-shot (one Control, fixed dom grounded once, $SHOTS shots, n=$MAXMODELS, ${CLINGO_TIMEOUT}s budget) ===="
        with_timeout "$CLINGO_TIMEOUT" python3 "$ROOT/clingo-multishot-constraints.py" \
                "$ENCODING" "$INSTPATH" "$SHOTS" "$FORBID" "$MAXMODELS" "$FORBIDTOTAL" "$ORDER" > "$MSS_OUT" 2>&1
        rc=$?
        if [[ "$rc" == "0" ]]; then
            cat "$MSS_OUT"
            mss_total=$(grep "wall time (incl. base setup)" "$MSS_OUT" | grep -oE "[0-9]+\.[0-9]+s" | tr -d 's' | head -1)
            mss_counts=$(grep -E '^[0-9]' "$MSS_OUT" | awk -F'|' '{gsub(/ /,"",$4); print $4}' | paste -sd, -)
        else
            tail -3 "$MSS_OUT" 2>/dev/null
            echo "  clingo multi-shot FAILED (base |dom|^6 grounding did not finish within ${CLINGO_TIMEOUT}s)"
        fi
    fi
fi

# Totals + soundness.
live_total=$(grep "total alpha time" "$LIVE_OUT" | grep -oE "[0-9]+\.[0-9]+s" | head -1 | tr -d 's')
batch_total=$(grep "total alpha time" "$BATCH_OUT" | grep -oE "[0-9]+\.[0-9]+s" | head -1 | tr -d 's')
counts_live=$(grep -E '^[0-9]' "$LIVE_OUT" | awk -F'|' '{gsub(/ /,"",$4); print $4}' | paste -sd, -)
counts_batch=$(grep -E '^[0-9]' "$BATCH_OUT" | awk -F'|' '{gsub(/ /,"",$4); print $4}' | paste -sd, -)

if [[ "$RUN_CLINGO" == "1" ]]; then
    clingo_disp="FAILED"; [[ -n "$clingo_total" ]] && clingo_disp="${clingo_total}s"
    mss_disp="FAILED"; [[ "$mss_total" != "TIMEOUT" && -n "$mss_total" ]] && mss_disp="${mss_total}s"
else
    clingo_disp="skipped (RUN_CLINGO=0)"; mss_disp="skipped (RUN_CLINGO=0)"
fi

echo
echo "==== summary (instance=$INSTANCE, shots=$SHOTS, forbid/shot=$FORBID, forbidTotal=$FORBIDTOTAL of $NEL [$((NEL - FORBIDTOTAL)) left unconstrained], n=$MAXMODELS) ===="
printf "  incremental Alpha (live session):     %ss\n" "$live_total"
printf "  regular Alpha (rebuild each shot):    %ss\n" "$batch_total"
printf "  clingo (rebuilt each shot):           %s\n" "$clingo_disp"
printf "  clingo multi-shot (one Control):      %s\n" "$mss_disp"
if [[ -n "$live_total" && -n "$batch_total" ]]; then
    printf "  speedup live vs batch (batch/live):   %.2fx\n" \
        "$(python3 -c "print($batch_total / $live_total)")"
fi

echo
echo "==== answer-set count agreement (soundness) ===="
ok=1
printf "  live:        %s\n" "$counts_live"
printf "  batch:       %s\n" "$counts_batch"
[[ "$counts_live" != "$counts_batch" ]] && ok=0
if [[ -n "$clingo_counts" ]]; then
    printf "  clingo:      %s\n" "$clingo_counts"
    [[ "$clingo_counts" != "$counts_live" ]] && ok=0
fi
if [[ -n "$mss_counts" ]]; then
    printf "  clingo MSS:  %s\n" "$mss_counts"
    [[ "$mss_counts" != "$counts_live" ]] && ok=0
fi
if (( ok )); then
    echo "  => all available count columns agree: yes"
else
    echo "  => MISMATCH (see columns above)"
fi
