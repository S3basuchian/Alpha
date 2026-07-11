#!/usr/bin/env bash
# Four-way reach sweep. For each instance of the Leutgeb & Weinzierl (2018)
# Table-5 grid, stream its edges into an initially-empty graph over SHOTS shots
# and report the total time under four solvers, all on the SAME per-instance
# stream:
#
#   inc Alpha   - one live AlphaSession, solver retained across shots
#   batch Alpha - AlphaSession rebuilt from scratch each shot (same JVM)
#   clingo      - clingo re-invoked from scratch each shot
#   clingo MSS  - clingo multi-shot: one long-lived Control, edges streamed via
#                 incremental add+ground with NO externals (the fair apples-to-
#                 apples with Alpha's session; see clingo-multishot.py)
#
# The paper's single-shot Table-5 numbers (independent instances, 2018 cluster)
# are shown on the right as a reference only.
#
# Usage:
#   ./bench-reach-sweep.sh              # 25 shots per instance, whole grid
#   SHOTS=50 ./bench-reach-sweep.sh
#   ./bench-reach-sweep.sh edges-rand-v1000-e8000.lp   # a single instance

set -u

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$ROOT/../.." && pwd)"
ENCODING="$ROOT/encoding.lp"
CLINGO="${CLINGO:-clingo}"
SHOTS="${SHOTS:-25}"

# grid entry = file:label:edges
GRID=(
    "edges-rand-v1000-e4000.lp:1000/4:4000"
    "edges-rand-v1000-e8000.lp:1000/8:8000"
    "edges-rand-v10000-e20000.lp:10000/2:20000"
    "edges-rand-v10000-e40000.lp:10000/4:40000"
    "edges-rand-v10000-e80000.lp:10000/8:80000"
)
# Optional single-instance restriction (match by file name substring).
if [[ $# -ge 1 ]]; then
    filtered=(); for e in "${GRID[@]}"; do [[ "$e" == *"$1"* ]] && filtered+=("$e"); done
    GRID=("${filtered[@]}")
fi

# Paper Table-5 reference (case lookups, so the script runs on bash 3.2 too).
paper_alpha() { case "$1" in
    1000/4) echo 2.13;; 1000/8) echo 3.19;; 10000/2) echo 10.95;;
    10000/4) echo 13.06;; 10000/8) echo 16.62;; *) echo "?";; esac; }
paper_clingo() { case "$1" in
    1000/4) echo 0.11;; 1000/8) echo 0.21;; 10000/2) echo 0.52;;
    10000/4) echo 1.09;; 10000/8) echo 2.27;; *) echo "?";; esac; }

have_clingo=1; command -v "$CLINGO" >/dev/null 2>&1 || have_clingo=0
have_clingo_py=0; python3 -c 'import clingo' 2>/dev/null && have_clingo_py=1

TMP_EDGES="$(mktemp)"; trap 'rm -f "$TMP_EDGES"' EXIT

# Total seconds from the Java driver for a given mode (live|batch).
run_alpha() {
    (cd "$REPO_ROOT" && ./gradlew --quiet :alpha-cli-app:runIncrementalReachBenchmark \
        --args="$ENCODING $1 $SHOTS $2" 2>/dev/null) \
        | grep 'total alpha time' | sed -E 's/.*: ([0-9.]+)s.*/\1/'
}

# clingo re-invoked from scratch each shot; echoes the summed wall time.
run_clingo_rebuilt() {
    local inst="$1"
    local total_lines base rem off=0 total=0
    total_lines=$(grep -c '^edge(' "$inst")
    base=$(( total_lines / SHOTS )); rem=$(( total_lines % SHOTS ))
    : > "$TMP_EDGES"
    for ((s=1; s<=SHOTS; s++)); do
        local size=$base; (( s <= rem )) && size=$(( base + 1 ))
        grep '^edge(' "$inst" | sed -n "$((off + 1)),$((off + size))p" >> "$TMP_EDGES"
        off=$(( off + size ))
        local t0 t1
        t0=$(python3 -c 'import time;print(time.time())')
        "$CLINGO" --quiet=2,2 --number=1 --time-limit=300 "$ENCODING" "$TMP_EDGES" >/dev/null 2>&1
        t1=$(python3 -c 'import time;print(time.time())')
        total=$(python3 -c "print(f'{$total + ($t1 - $t0):.3f}')")
    done
    echo "$total"
}

# clingo multi-shot wall time (incremental add+ground, no externals).
run_mss() {
    python3 "$ROOT/clingo-multishot.py" "$ENCODING" "$1" "$SHOTS" 2>&1 \
        | grep 'wall time' | sed -E 's/.*: ([0-9.]+)s.*/\1/'
}

echo
echo "==== reach 4-way sweep (each instance streamed from empty, $SHOTS shots) ===="
printf "%-9s | %-7s | %-10s | %-11s | %-9s | %-11s || %-11s | %-10s\n" \
    "instance" "edges" "inc Alpha" "batch Alpha" "clingo" "clingo MSS" "paper Alpha" "paper clingo"
printf -- "----------+---------+------------+-------------+-----------+-------------++-------------+-------------\n"

for entry in "${GRID[@]}"; do
    IFS=':' read -r file label edges <<< "$entry"
    inst="$ROOT/instances/$file"
    [[ -f "$inst" ]] || { echo "  (missing $file — run gen-random-graph.py --grid)"; continue; }

    live=$(run_alpha "$inst" live)
    batch=$(run_alpha "$inst" batch)
    clingo="n/a"; [[ "$have_clingo" == 1 ]] && clingo=$(run_clingo_rebuilt "$inst")
    mss="n/a"; [[ "$have_clingo_py" == 1 ]] && mss=$(run_mss "$inst")

    printf "%-9s | %-7s | %-10s | %-11s | %-9s | %-11s || %-11s | %-10s\n" \
        "$label" "$edges" "${live:-?}" "${batch:-?}" "$clingo" "$mss" \
        "$(paper_alpha "$label")" "$(paper_clingo "$label")"
done

echo
echo "  inc Alpha / batch Alpha: same JVM (batch rebuilds the session each shot)."
echo "  clingo / clingo MSS: subprocess per shot / one long-lived Control."
echo "  paper columns: L&W 2018 Table 5 single-shot, independent instances, 2018 cluster — reference only."
