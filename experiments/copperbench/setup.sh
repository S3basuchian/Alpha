#!/usr/bin/env bash
# One-shot setup for the copperbench experiments. Run once, on the cluster head node,
# after checking out the repo:
#
#     bash experiments/copperbench/setup.sh
#
# It (1) builds the standalone Alpha classpath, (2) pre-generates every benchmark instance
# deterministically (so the parallel SLURM jobs never race to create a file), and (3) renders
# the copperbench <bench>.json config files from the <bench>.json.in templates, baking in the
# absolute checkout path and the cluster-specific fields.
#
# Cluster override (env var, optional):
#     PARTITION   SLURM partition (default: broadwell) — pins every run to one CPU type so the
#                 timings are comparable. runsolver/clearcache use copperbench's own defaults.
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$HERE/../.." && pwd)"
EXAMPLES="$REPO_ROOT/examples"
PY="${PYTHON:-python3}"

PARTITION="${PARTITION:-broadwell}"

echo "==> repo root: $REPO_ROOT"

# 1. Build the standalone Alpha distribution (no gradle needed at run time thereafter).
echo "==> building alpha-cli-app (installDist) ..."
( cd "$REPO_ROOT" && ./gradlew --quiet :alpha-cli-app:installDist )
LIBDIR="$REPO_ROOT/alpha-cli-app/build/install/alpha-cli-app/lib"
[[ -d "$LIBDIR" ]] || { echo "ERROR: $LIBDIR missing after installDist" >&2; exit 1; }
echo "    classpath: $LIBDIR"

# 2. Pre-generate all instances deterministically.
echo "==> generating instances ..."

# groundexp: dom-N.lp = dom(1..N).
mkdir -p "$EXAMPLES/groundexp/instances"
for N in 8 10 12 14 16 18 20 500 1000; do
    f="$EXAMPLES/groundexp/instances/dom-$N.lp"
    [[ -f "$f" ]] || "$PY" "$EXAMPLES/groundexp/gen_dom.py" "$N" > "$f"
done

# cutedge: gen_cutedge.py writes instances/edges-<V>-<pct>.lp itself (seed 42).
for pair in 100-30 100-50 200-30 200-50 300-10 300-30 500-10 500-30 500-50; do
    V="${pair%-*}"; P="${pair#*-}"
    f="$EXAMPLES/cutedge/instances/edges-$V-$P.lp"
    [[ -f "$f" ]] || "$PY" "$EXAMPLES/cutedge/gen_cutedge.py" "$V" "$P" 42 >/dev/null
done

# reach: random directed graph, seed 0 (matches the sweep grid file names).
mkdir -p "$EXAMPLES/reach/instances"
for pair in 1000:4 1000:8 10000:2 10000:4 10000:8; do
    V="${pair%:*}"; M="${pair#*:}"; E=$(( V * M ))
    f="$EXAMPLES/reach/instances/edges-rand-v$V-e$E.lp"
    [[ -f "$f" ]] || "$PY" "$EXAMPLES/reach/gen-random-graph.py" --vertices "$V" --edges "$E" --seed 0 > "$f"
done

# coloring: no instance files — the Java driver generates the base graph internally from (V,E,seed).

# 3. Render the copperbench JSON configs from templates.
echo "==> rendering copperbench configs (partition=$PARTITION) ..."
for b in groundexp cutedge reach coloring; do
    sed -e "s#__REPO_ROOT__#$REPO_ROOT#g" \
        -e "s#__PARTITION__#$PARTITION#g" \
        "$HERE/$b.json.in" > "$HERE/$b.json"
    echo "    wrote $HERE/$b.json"
done

chmod +x "$HERE"/wrappers/run-*.sh 2>/dev/null || true

cat <<EOF

==> setup complete.

Next (from the repo root, with copperbench installed — see README):
    copperbench experiments/copperbench/groundexp.json
    copperbench experiments/copperbench/cutedge.json
    copperbench experiments/copperbench/reach.json
    copperbench experiments/copperbench/coloring.json
Then submit each generated folder:  ( cd groundexp && bash submit_all.sh )   # etc.

After the runs finish, collect results:
    python3 experiments/copperbench/postprocess/collect.py groundexp cutedge reach coloring
EOF
