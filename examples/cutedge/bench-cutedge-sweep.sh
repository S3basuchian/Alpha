#!/usr/bin/env bash
# Cutedge iterative edge-cutting (retraction) loop, swept over the full L&W 2018
# Table-2 instance set, four-way:
#
#   live        incremental Alpha  — one long-lived AlphaSession; removeFacts the
#                                    chosen edge each shot, grounder/store/VSIDS warm.
#   batch       regular Alpha      — fresh session rebuilt each shot from
#                                    encoding + surviving edges.
#   clingo      rebuilt clingo     — fresh clingo.Control each shot, re-grounded
#                                    from scratch (pays the O(V^3) reachability
#                                    grounding every shot).
#   clingo MSS  multi-shot clingo  — one Control; every edge an #external set true,
#                                    grounded ONCE, each cut flips an external false.
#
# Every mode drives its OWN cut sequence (first-found answer set), so the modes
# follow independent trajectories; the reported number is total wall-clock over
# SHOTS shots. Each mode has a hard TIMEOUT (default 180s); on timeout the cell is
# "t/o". clingo modes are pruned forward once they time out (grounding cost is
# V-monotone and the sweep is V-ascending), matching L&W Table 2's many timeouts.
#
# Usage:
#   ./bench-cutedge-sweep.sh                 # all 9 paper instances, 10 shots
#   SHOTS=10 TIMEOUT=180 ./bench-cutedge-sweep.sh
#   INSTANCES="100-30 100-50" ./bench-cutedge-sweep.sh   # subset

set -u

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$ROOT/../.." && pwd)"

ENCODING="$ROOT/encoding.lp"
CP="$REPO_ROOT/alpha-cli-app/build/install/alpha-cli-app/lib/*"
MAIN="at.ac.tuwien.kr.alpha.app.examples.IncrementalCutedgeRetractionBenchmark"

SHOTS="${SHOTS:-10}"
WARMUPS="${WARMUPS:-1}"
TIMEOUT="${TIMEOUT:-180}"
CLINGO="${CLINGO:-clingo}"
# L&W 2018 Table 2 order: V ascending, density ascending within V.
INSTANCES="${INSTANCES:-100-30 100-50 200-30 200-50 300-10 300-30 500-10 500-30 500-50}"

LOGDIR="$(mktemp -d)"
echo "logs: $LOGDIR" >&2

# Hard wall-clock kill (TERM then KILL). Returns 124 on timeout, else the child's code.
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

# Extract "<number>s" following a label from a (possibly partial) log file; empty if absent.
grab() { grep -oE "$1[^0-9]*[0-9]+\.[0-9]+s" "$2" 2>/dev/null | grep -oE '[0-9]+\.[0-9]+' | head -1; }

declare -a ROWS
rebuilt_dead=0
mss_dead=0

for inst in $INSTANCES; do
    edges="$ROOT/instances/edges-$inst.lp"
    V="${inst%-*}"; D="${inst#*-}"
    if [[ ! -f "$edges" ]]; then echo "missing $edges, skipping" >&2; continue; fi
    echo "=== $inst (V=$V, density=$D%) ===" >&2

    # --- Alpha live + batch (one JVM prints both) ---
    alog="$LOGDIR/alpha-$inst.log"
    echo "  alpha live+batch ..." >&2
    with_timeout "$TIMEOUT" java -XX:MaxRAM=8000M -Xmx3500M -cp "$CP" "$MAIN" \
        "$ENCODING" "$edges" "$SHOTS" "$WARMUPS" > "$alog" 2>&1
    live="$(grab 'total alpha time \(incremental\):' "$alog")"
    batch="$(grab 'total alpha time \(batch\):' "$alog")"
    [[ -z "$live" ]]  && live="t/o"  || live="${live}s"
    [[ -z "$batch" ]] && batch="t/o" || batch="${batch}s"
    echo "    live=$live batch=$batch" >&2

    # --- clingo rebuilt ---
    if (( rebuilt_dead )); then
        clingo="t/o"
    else
        clog="$LOGDIR/clingo-rebuilt-$inst.log"
        echo "  clingo rebuilt ..." >&2
        with_timeout "$TIMEOUT" python3 "$ROOT/clingo-cutedge.py" "$ENCODING" "$edges" "$SHOTS" rebuilt > "$clog" 2>&1
        clingo="$(grab 'total clingo rebuilt time:' "$clog")"
        if [[ -z "$clingo" ]]; then clingo="t/o"; rebuilt_dead=1; echo "    clingo rebuilt t/o -> pruned for larger V" >&2
        else clingo="${clingo}s"; echo "    clingo=$clingo" >&2; fi
    fi

    # --- clingo MSS ---
    if (( mss_dead )); then
        mss="t/o"
    else
        mlog="$LOGDIR/clingo-mss-$inst.log"
        echo "  clingo MSS ..." >&2
        with_timeout "$TIMEOUT" python3 "$ROOT/clingo-cutedge.py" "$ENCODING" "$edges" "$SHOTS" mss > "$mlog" 2>&1
        mss="$(grab 'total clingo MSS wall time \(incl. base setup\):' "$mlog")"
        if [[ -z "$mss" ]]; then mss="t/o"; mss_dead=1; echo "    clingo MSS t/o -> pruned for larger V" >&2
        else mss="${mss}s"; echo "    clingo MSS=$mss" >&2; fi
    fi

    ROWS+=("$(printf '%-10s | %-10s | %-10s | %-12s | %-12s' "$V/$D" "$live" "$batch" "$clingo" "$mss")")
done

echo
echo "==== cutedge four-way sweep (shots=$SHOTS, warmups=$WARMUPS, timeout=${TIMEOUT}s/mode, own cut sequences) ===="
printf '%-10s | %-10s | %-10s | %-12s | %-12s\n' "V/dens" "live" "batch" "clingo" "clingo MSS"
printf '%-10s-+-%-10s-+-%-10s-+-%-12s-+-%-12s\n' "----------" "----------" "----------" "------------" "------------"
for r in "${ROWS[@]}"; do echo "$r"; done
echo
echo "  live/batch = incremental vs from-scratch Alpha; clingo = rebuilt each shot; clingo MSS = one Control, edges as externals."
echo "  t/o = exceeded ${TIMEOUT}s wall clock for that mode (clingo modes pruned forward once timed out)."
echo "  full per-instance logs: $LOGDIR"
