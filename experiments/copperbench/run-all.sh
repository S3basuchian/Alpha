#!/usr/bin/env bash
# One-shot driver: wipe old copperbench results, rebuild + regenerate everything, then generate and
# submit the SLURM jobs for ALL six benchmarks. Run from the cluster head node after checking out
# the repo (copperbench must be installed and on PATH — see README):
#
#     bash experiments/copperbench/run-all.sh
#
# Honors the same env overrides as setup.sh (passed straight through):
#     PARTITION     SLURM partition (default sunnycove)
#     NUM_SAMPLES   random instances per size (default 10)
#     BASE_SEED     first sample seed (default 42)
#     PYTHON        python interpreter (default python3)
#     RUN_DIR       where the <bench>/ output trees are created (default: repo root)
#
# NOTE: this removes the previous <bench>/ output trees and result files, but does NOT cancel jobs
# still queued/running from an earlier submission. If a previous batch is still in the queue, cancel
# it first (e.g. `scancel -u "$USER"`, if no unrelated jobs of yours are running) before re-running.
#
# After the submitted jobs finish, collect results with:
#     python3 experiments/copperbench/postprocess/collect.py --results-dir <RUN_DIR>
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$HERE/../.." && pwd)"
RUN_DIR="${RUN_DIR:-$REPO_ROOT}"
BENCHES=(groundexp cutedge reach coloring coloring-grow walk)

command -v copperbench >/dev/null 2>&1 || {
    echo "ERROR: 'copperbench' not found on PATH — install it first (pip install . from a copperbench checkout; see README)." >&2
    exit 1
}

# copperbench writes each <bench>/ tree into the CWD, so run everything from RUN_DIR and point the
# collector there afterwards.
mkdir -p "$RUN_DIR"
cd "$RUN_DIR"
echo "==> output dir: $RUN_DIR"

echo "==> [1/4] removing old results ..."
for b in "${BENCHES[@]}"; do rm -rf "${RUN_DIR:?}/$b"; done
rm -f "$RUN_DIR"/results_long.csv "$RUN_DIR"/results_wide.csv "$RUN_DIR"/tables.tex

echo "==> [2/4] build + generate instances + render configs (setup.sh) ..."
bash "$HERE/setup.sh"

echo "==> [3/4] generating SLURM job trees (copperbench) ..."
for b in "${BENCHES[@]}"; do
    echo "    copperbench $b.json"
    copperbench "$HERE/$b.json"
done

echo "==> [4/4] submitting all jobs ..."
for b in "${BENCHES[@]}"; do
    [[ -f "$RUN_DIR/$b/submit_all.sh" ]] || { echo "ERROR: $RUN_DIR/$b/submit_all.sh missing" >&2; exit 1; }
    echo "    submitting $b ..."
    ( cd "$RUN_DIR/$b" && bash submit_all.sh )
done

cat <<EOF

==> all five benchmarks submitted from $RUN_DIR.
    watch the queue:   squeue -u "\$USER"
    when finished, collect (CSV + tables.tex):
        python3 experiments/copperbench/postprocess/collect.py --results-dir "$RUN_DIR"
EOF
