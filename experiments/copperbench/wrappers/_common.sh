# Shared helpers for the copperbench benchmark wrappers.
# Sourced by run-<benchmark>.sh. Not executable on its own.
#
# Each wrapper is invoked by copperbench as:
#     bash <abs>/run-<benchmark>.sh <config-token> <instance-params...>
# where <config-token> is one of: alpha-mss | alpha-rebuilt | clingo-rebuilt | clingo-mss
#
# The wrapper runs exactly ONE solver configuration for ONE instance and prints the
# solver's own "overall runtime" (summed over shots, matching the paper methodology,
# excluding JVM/gradle startup) as a single canonical line:
#     RESULT_SECONDS=<float>
# On a time-out (30 min wall) or mem-out (64 GB) the process is killed by runsolver before that line
# is printed, so its absence marks a Timeout/Memout cell (collect.py reads which cap was hit).

set -u

# Resolve the repository root from this file's absolute location:
#   <root>/experiments/copperbench/wrappers/_common.sh  ->  <root>
_COMMON_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$_COMMON_DIR/../../.." && pwd)"
EXAMPLES="$REPO_ROOT/examples"

# Built Alpha classpath (produced by `./gradlew :alpha-cli-app:installDist`, run by setup.sh).
INSTALL_LIB="$REPO_ROOT/alpha-cli-app/build/install/alpha-cli-app/lib/*"

# JVM options. The Java heap is sized to use the full node memory: -Xmx is generous but kept a few
# GB under the 64 GB runsolver mem cap (-XX:MaxRAM reflects the node RAM) so a genuine blow-up is
# caught by runsolver (Memout) rather than an early JVM OutOfMemoryError — collect.py classifies
# both as Memout. The headroom also leaves room for JVM non-heap/native and, in the clingo configs,
# the clingo subprocess. Override via JVM_XMX if needed.
JVM_XMX="${JVM_XMX:-60g}"
JVM_OPTS="-XX:MaxRAM=64000M -Xmx${JVM_XMX}"

# clingo binary + python interpreter (override on clusters with module-loaded tools).
CLINGO="${CLINGO:-clingo}"
PYTHON="${PYTHON:-python3}"

MAIN_PKG="at.ac.tuwien.kr.alpha.app.examples"

# announce <bench> <instance-label> — self-identifying lines the postprocessor keys on, so it
# never has to depend on copperbench's run-directory naming. Uses the wrapper's $CONFIG.
announce() { echo "RESULT_BENCH=$1"; echo "RESULT_CONFIG=${CONFIG:-?}"; echo "RESULT_INSTANCE=$2"; }

# emit <seconds> — print the one canonical result line the postprocessor greps for.
emit() { echo "RESULT_SECONDS=$1"; }

# grab_seconds <label-regex> <logfile> — the sweep scripts' extractor: first "<n>.<n>s"
# following <label> in <logfile>, without the trailing 's'. Empty if absent.
grab_seconds() {
    grep -oE "$1[^0-9]*[0-9]+\.[0-9]+s" "$2" 2>/dev/null | grep -oE '[0-9]+\.[0-9]+' | head -1
}

# run_java <mainClassSimpleName> <args...> — run a built Alpha benchmark driver, streaming
# its output to stdout (captured into copperbench's stdout.log) and to a temp log we can grep.
# Sets JAVA_LOG to the temp path.
JAVA_LOG=""
run_java() {
    local main="$1"; shift
    JAVA_LOG="$(mktemp)"
    # shellcheck disable=SC2086
    java $JVM_OPTS ${JVM_EXTRA:-} -cp "$INSTALL_LIB" "$MAIN_PKG.$main" "$@" 2>&1 | tee "$JAVA_LOG"
}

die_unsupported() {
    echo "UNSUPPORTED: $*" >&2
    exit 3
}
