#!/usr/bin/env bash
# One-shot setup for the copperbench experiments. Run once, on the cluster head node,
# after checking out the repo:
#
#     bash experiments/copperbench/setup.sh
#
# It (1) builds the standalone Alpha classpath, (2) expands each committed <bench>.sizes grid
# into a <bench>.instances list with a per-sample SEED column and pre-generates every seeded
# benchmark instance deterministically (so the parallel SLURM jobs never race to create a file),
# and (3) renders the copperbench <bench>.json config files from the <bench>.json.in templates,
# baking in the absolute checkout path and the cluster-specific fields.
#
# Random sampling (each instance SIZE is run on NUM_SAMPLES independently-seeded random
# instances; all four solver configs see the identical seeded instance, so the comparison
# stays 1:1 per sample). Env overrides:
#     BASE_SEED     first seed (default 42); sample k uses seed BASE_SEED+k
#     NUM_SAMPLES   random instances per size (default 10) — the paper averages over 10
#     PARTITION     SLURM partition (default: sunnycove) — pins every run to one CPU type so the
#                   timings are comparable. runsolver/clearcache use copperbench's own defaults.
#
# What the seed varies, per benchmark:
#     groundexp-  nothing — model-dependent forbid-all is deterministic (block every found
#      as{2,10}   selection), so the seed is only a timing repetition (the dom(1..N) instance is fixed).
#     cutedge     the random graph (gen_cutedge.py seed). Each solver still drives its own
#                 answer-set cut sequence on that shared graph (own-sequence methodology).
#     reach       the random directed graph (gen-random-graph.py seed) -> which edges arrive.
#     coloring    base graph + the model-dependent edit stream, generated inside the Java driver
#     coloring-   from (V,E,seed); clingo replays Alpha's per-shot dump, so all four align.
#       grow
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$HERE/../.." && pwd)"
EXAMPLES="$REPO_ROOT/examples"
PY="${PYTHON:-python3}"

PARTITION="${PARTITION:-sunnycove}"
BASE_SEED="${BASE_SEED:-42}"
NUM_SAMPLES="${NUM_SAMPLES:-10}"

echo "==> repo root: $REPO_ROOT"
echo "==> sampling: NUM_SAMPLES=$NUM_SAMPLES seeds $BASE_SEED..$((BASE_SEED + NUM_SAMPLES - 1))"

# 1. Build the standalone Alpha distribution (no gradle needed at run time thereafter).
echo "==> building alpha-cli-app (installDist) ..."
( cd "$REPO_ROOT" && ./gradlew --quiet :alpha-cli-app:installDist )
LIBDIR="$REPO_ROOT/alpha-cli-app/build/install/alpha-cli-app/lib"
[[ -d "$LIBDIR" ]] || { echo "ERROR: $LIBDIR missing after installDist" >&2; exit 1; }
echo "    classpath: $LIBDIR"

# seeds — the shared BASE_SEED..BASE_SEED+NUM_SAMPLES-1 sequence, reused for every size.
SEEDS=(); for ((k=0; k<NUM_SAMPLES; k++)); do SEEDS+=($((BASE_SEED + k))); done

# read_sizes <bench> — echo the non-empty, non-comment lines of <bench>.sizes (the size grid).
read_sizes() { grep -vE '^\s*(#|$)' "$HERE/$1.sizes"; }

# 2. Expand each size grid into a seeded <bench>.instances and pre-generate the seeded inputs.
echo "==> expanding size grids x $NUM_SAMPLES seeds and generating instances ..."

# groundexp-as{2,10}: dom-N.lp = dom(1..N). Model-dependent forbid-all is deterministic, so the
# seed is only a timing-repetition here (the instance does not vary). Size grid line: "<N> <shots>";
# instance line: "<N> <shots> <seed>". Same grid for both maxAS variants (maxAS is fixed by the wrapper).
mkdir -p "$EXAMPLES/groundexp/instances"
for b in groundexp-as2 groundexp-as10; do
    : > "$HERE/$b.instances"
    while read -r N SHOTS; do
        f="$EXAMPLES/groundexp/instances/dom-$N.lp"
        [[ -f "$f" ]] || "$PY" "$EXAMPLES/groundexp/gen_dom.py" "$N" > "$f"
        for s in "${SEEDS[@]}"; do echo "$N $SHOTS $s" >> "$HERE/$b.instances"; done
    done < <(read_sizes "$b")
done

# cutedge: one random graph per (V,pct,seed). gen_cutedge.py writes edges-<V>-<pct>.lp (fixed
# name); rename to the seeded name so the 10 samples coexist. Instance line: "<V> <pct> <seed>".
mkdir -p "$EXAMPLES/cutedge/instances"
: > "$HERE/cutedge.instances"
while read -r V P; do
    for s in "${SEEDS[@]}"; do
        f="$EXAMPLES/cutedge/instances/edges-$V-$P-s$s.lp"
        if [[ ! -f "$f" ]]; then
            "$PY" "$EXAMPLES/cutedge/gen_cutedge.py" "$V" "$P" "$s" >/dev/null
            mv "$EXAMPLES/cutedge/instances/edges-$V-$P.lp" "$f"
        fi
        echo "$V $P $s" >> "$HERE/cutedge.instances"
    done
done < <(read_sizes cutedge)

# reach: one random directed graph per (V,Emult,seed). Instance line: "<V> <Emult> <seed>".
mkdir -p "$EXAMPLES/reach/instances"
: > "$HERE/reach.instances"
while read -r V M; do
    E=$(( V * M ))
    for s in "${SEEDS[@]}"; do
        f="$EXAMPLES/reach/instances/edges-rand-v$V-e$E-s$s.lp"
        [[ -f "$f" ]] || "$PY" "$EXAMPLES/reach/gen-random-graph.py" --vertices "$V" --edges "$E" --seed "$s" > "$f"
        echo "$V $M $s" >> "$HERE/reach.instances"
    done
done < <(read_sizes reach)

# coloring / coloring-grow: no instance files — the Java driver generates the base graph + edit
# stream internally from (V,E,seed). Just expand the instance lists.
#   coloring       line: "<V> <E> <seed>"
#   coloring-grow  line: "<V> <E> <SHOTS> <seed>"
: > "$HERE/coloring.instances"
while read -r V E; do
    for s in "${SEEDS[@]}"; do echo "$V $E $s" >> "$HERE/coloring.instances"; done
done < <(read_sizes coloring)

: > "$HERE/coloring-grow.instances"
while read -r V E SHOTS; do
    for s in "${SEEDS[@]}"; do echo "$V $E $SHOTS $s" >> "$HERE/coloring-grow.instances"; done
done < <(read_sizes coloring-grow)

for b in groundexp-as2 groundexp-as10 cutedge reach coloring coloring-grow; do
    echo "    $b.instances: $(wc -l < "$HERE/$b.instances") lines"
done

# 3. Render the copperbench JSON configs from templates.
echo "==> rendering copperbench configs (partition=$PARTITION) ..."
for b in groundexp-as2 groundexp-as10 cutedge reach coloring coloring-grow; do
    sed -e "s#__REPO_ROOT__#$REPO_ROOT#g" \
        -e "s#__PARTITION__#$PARTITION#g" \
        "$HERE/$b.json.in" > "$HERE/$b.json"
    echo "    wrote $HERE/$b.json"
done

chmod +x "$HERE"/wrappers/run-*.sh 2>/dev/null || true

cat <<EOF

==> setup complete.

Next (from the repo root, with copperbench installed — see README):
    copperbench experiments/copperbench/groundexp-as2.json
    copperbench experiments/copperbench/groundexp-as10.json
    copperbench experiments/copperbench/cutedge.json
    copperbench experiments/copperbench/reach.json
    copperbench experiments/copperbench/coloring.json
    copperbench experiments/copperbench/coloring-grow.json
Then submit each generated folder:  ( cd groundexp && bash submit_all.sh )   # etc.

After the runs finish, collect results (mean over the $NUM_SAMPLES samples per size):
    python3 experiments/copperbench/postprocess/collect.py groundexp-as2 groundexp-as10 cutedge reach coloring coloring-grow
EOF
