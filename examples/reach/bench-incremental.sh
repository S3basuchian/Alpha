#!/usr/bin/env bash
# Incremental reach benchmark: drive both Alpha (via AlphaSession) and clingo
# (via repeated invocations) over a growing edge set, and report per-shot timings.
#
# Usage:
#   ./bench-incremental.sh                                   # default: 5 shots on edges-rand-v1000-e8000.lp
#   ./bench-incremental.sh edges-rand-v10000-e40000.lp 50
#   SHOTS=50 ./bench-incremental.sh edges-rand-v1000-e8000.lp
#
# The Alpha column is the per-shot time as reported by IncrementalReachBenchmark
# (a single JVM, AlphaSession growing across shots).
# The clingo column is `clingo <encoding> <growing-edges-file>` invoked from
# scratch for each shot — the realistic shell-level baseline. (Multi-shot via
# clingo's Python API would also re-ground per shot; we compare the simpler
# common case.)

set -u

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$ROOT/../.." && pwd)"

ENCODING="$ROOT/encoding.lp"
ALPHA_CLI="$REPO_ROOT/alpha-cli-app/build/install/alpha-cli-app/bin/alpha-cli-app"
CLINGO="${CLINGO:-clingo}"

INSTANCE="${1:-edges-rand-v1000-e8000.lp}"
SHOTS="${SHOTS:-${2:-5}}"

INSTPATH="$ROOT/instances/$INSTANCE"
if [[ ! -f "$INSTPATH" ]]; then
    echo "Instance not found: $INSTPATH" >&2
    exit 1
fi
if ! command -v "$CLINGO" >/dev/null 2>&1; then
    echo "clingo not found in PATH." >&2
    exit 1
fi

# --- 1. Drive Alpha via the Java incremental benchmark --------------------

echo
echo "==== Alpha (AlphaSession, single JVM, $SHOTS shots) ===="

ALPHA_OUT="$(mktemp)"
trap 'rm -f "$ALPHA_OUT" "$TMP_EDGES" "$TMP_EDGES_ALPHA"' EXIT

(cd "$REPO_ROOT" && ./gradlew --quiet :alpha-cli-app:runIncrementalReachBenchmark \
        --args="$ENCODING $INSTPATH $SHOTS") | tee "$ALPHA_OUT"

# --- 2. Drive clingo by re-invoking on a growing edge file ----------------

echo
echo "==== clingo (re-invoked from scratch, $SHOTS shots) ===="

# Same chunking as IncrementalReachBenchmark: split lines into $SHOTS roughly-equal chunks.
TOTAL_LINES=$(grep -v '^\s*$' "$INSTPATH" | grep -v '^\s*%' | wc -l | awk '{print $1}')
BASE=$(( TOTAL_LINES / SHOTS ))
REM=$(( TOTAL_LINES % SHOTS ))

TMP_EDGES="$(mktemp)"
: > "$TMP_EDGES"

printf "%-6s | %-10s | %-12s | %-14s\n" "shot" "edges +" "edges total" "clingo (s)"
printf "%-6s-+-%-10s-+-%-12s-+-%-14s\n" "------" "----------" "------------" "--------------"

clingo_total=0
offset=0
for ((shot=1; shot<=SHOTS; shot++)); do
    chunk=$BASE
    if (( shot <= REM )); then chunk=$((BASE + 1)); fi

    # Append this chunk's lines to the growing edge file.
    grep -v '^\s*$' "$INSTPATH" | grep -v '^\s*%' \
        | sed -n "$((offset + 1)),$((offset + chunk))p" >> "$TMP_EDGES"
    offset=$((offset + chunk))

    t_start=$(python3 -c 'import time; print(time.time())')
    "$CLINGO" --quiet=2,2 --number=1 --time-limit=300 "$ENCODING" "$TMP_EDGES" >/dev/null 2>&1
    t_end=$(python3 -c 'import time; print(time.time())')
    elapsed=$(python3 -c "print(f'{$t_end - $t_start:.3f}')")
    clingo_total=$(python3 -c "print(f'{$clingo_total + $elapsed:.3f}')")

    printf "%-6d | %-10d | %-12d | %14.3f\n" "$shot" "$chunk" "$offset" "$elapsed"
done

echo
echo "  total clingo time: ${clingo_total}s over $SHOTS shots"

# --- 2b. Drive batch Alpha by re-invoking on the growing file --------------

echo
echo "==== Alpha batch (re-invoked from scratch, $SHOTS shots) ===="

TMP_EDGES_ALPHA="$(mktemp)"
: > "$TMP_EDGES_ALPHA"

printf "%-6s | %-10s | %-12s | %-14s\n" "shot" "edges +" "edges total" "alpha (s)"
printf "%-6s-+-%-10s-+-%-12s-+-%-14s\n" "------" "----------" "------------" "--------------"

alpha_batch_total=0
offset=0
for ((shot=1; shot<=SHOTS; shot++)); do
    chunk=$BASE
    if (( shot <= REM )); then chunk=$((BASE + 1)); fi

    grep -v '^\s*$' "$INSTPATH" | grep -v '^\s*%' \
        | sed -n "$((offset + 1)),$((offset + chunk))p" >> "$TMP_EDGES_ALPHA"
    offset=$((offset + chunk))

    t_start=$(python3 -c 'import time; print(time.time())')
    "$ALPHA_CLI" -i "$ENCODING" -i "$TMP_EDGES_ALPHA" -n 1 -q >/dev/null 2>&1
    t_end=$(python3 -c 'import time; print(time.time())')
    elapsed=$(python3 -c "print(f'{$t_end - $t_start:.3f}')")
    alpha_batch_total=$(python3 -c "print(f'{$alpha_batch_total + $elapsed:.3f}')")

    printf "%-6d | %-10d | %-12d | %14.3f\n" "$shot" "$chunk" "$offset" "$elapsed"
done

echo
echo "  total batch-Alpha time: ${alpha_batch_total}s over $SHOTS shots"

# --- 3. Summary -----------------------------------------------------------

alpha_total=$(grep -E "total alpha time" "$ALPHA_OUT" | awk '{print $5}' | tr -d 's')
if [[ -n "$alpha_total" ]]; then
    echo
    speedup_clingo=$(python3 -c "print(f'{$clingo_total / $alpha_total:.2f}x')")
    speedup_batch_alpha=$(python3 -c "print(f'{$alpha_batch_total / $alpha_total:.2f}x')")
    echo "==== summary ===="
    printf "  incremental alpha (live solver):  %ss\n" "$alpha_total"
    printf "  batch alpha (rebuilt each shot):  %ss   →   %s vs incremental\n" "$alpha_batch_total" "$speedup_batch_alpha"
    printf "  clingo (rebuilt each shot):       %ss   →   %s vs incremental\n" "$clingo_total" "$speedup_clingo"
fi
