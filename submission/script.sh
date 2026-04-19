#!/usr/bin/env bash

# PA4 entry-point script.

# Output layout (after running):
#   out/
#     class/<TestN>/
#       base/   — untransformed .class files (from javac)
#       opt/    — transformed .class files (from our optimizer)
#     jimple/<TestN>/
#       base/   — untransformed Jimple (Soot with --no-transform)
#       opt/    — transformed Jimple (Soot with Phase 3)
#
# Per testcase the script prints:
#   - the per-allocation `O<line> = Y[...]` verdicts from the analyzer
#   - base/opt wall-clock averaged over $ITERATIONS runs under `-Xint`

# Overrideable env vars:
#   SOOT_JAR       — path to Soot jar (default: ./soot-...jar)
#   ITERATIONS     — timed runs per test (default: 5)

set -eu
cd "$(dirname "$0")"

SOOT_JAR="${SOOT_JAR:-soot-4.6.0-jar-with-dependencies.jar}"
SRC_DIR="src"
BUILD_DIR="build"
TESTS_DIR="tests"
OUT_DIR="out"
CLASS_DIR="$OUT_DIR/class"
JIMPLE_DIR="$OUT_DIR/jimple"

ITERATIONS="${ITERATIONS:-5}"
JVM_FLAGS="-Xint"

if [ ! -f "$SOOT_JAR" ]; then
    echo "error: Soot jar not found at $SOOT_JAR" >&2
    exit 1
fi

# 1. Clean previous artifacts
rm -rf "$BUILD_DIR" "$OUT_DIR"
find "$SRC_DIR" "$TESTS_DIR" -name "*.class" -delete 2>/dev/null || true

# 2. Compile the optimizer
mkdir -p "$BUILD_DIR"
javac -cp "$SOOT_JAR" -sourcepath "$SRC_DIR" -d "$BUILD_DIR" "$SRC_DIR"/Main.java

mkdir -p "$CLASS_DIR" "$JIMPLE_DIR"

time_avg() {
    local cp="$1" main="$2"
    # warm-up
    java $JVM_FLAGS -cp "$cp" "$main" > /dev/null 2>&1 || true
    local total=0 t1 t2
    for _ in $(seq 1 "$ITERATIONS"); do
        t1=$(date +%s%N)
        java $JVM_FLAGS -cp "$cp" "$main" > /dev/null 2>&1 || true
        t2=$(date +%s%N)
        total=$((total + (t2 - t1) / 1000000))
    done
    echo $((total / ITERATIONS))
}

# 3. Per testcase compile, optimize, time, and check correctness.
printf "%-8s %10s %10s %10s %7s   %s\n" \
    "Test" "base(ms)" "opt(ms)" "speedup" "allocs" "correctness"
echo "-------------------------------------------------------------"

for src in $(ls "$TESTS_DIR"/Test*.java | sort -V); do
    [ -f "$src" ] || continue
    name=$(basename "$src" .java)

    class_base="$CLASS_DIR/$name/base"
    class_opt="$CLASS_DIR/$name/opt"
    jimple_base="$JIMPLE_DIR/$name/base"
    jimple_opt="$JIMPLE_DIR/$name/opt"
    mkdir -p "$class_base" "$class_opt" "$jimple_base" "$jimple_opt"

    # (a) Compile testcase → class_base
    if ! javac -d "$class_base" "$src" 2>/dev/null; then
        printf "%-8s  compile FAIL\n" "$name"
        continue
    fi

    # (b) Run optimizer once — emits transformed class files into $class_opt
    #     and both Jimple dumps (pre/post Phase 3) into the jimple dirs.
    results=$(java -cp "$BUILD_DIR:$SOOT_JAR" Main "$name" "$class_base" "$class_opt" \
                --jimple-base "$jimple_base" \
                --jimple-opt  "$jimple_opt" \
                2>/dev/null | grep -E "^O[0-9]+ = " || true)

    if [ ! -f "$class_opt/$name.class" ]; then
        printf "%-8s  transform FAIL (no %s.class emitted)\n" "$name" "$name"
        continue
    fi

    alloc_count=$(printf "%s\n" "$results" | grep -c "^O" || true)

    # (e) Time base vs. opt
    avg_base=$(time_avg "$class_base" "$name")
    avg_opt=$(time_avg "$class_opt"  "$name")

    # (f) Correctness: stdout must match
    base_out=$(java $JVM_FLAGS -cp "$class_base" "$name" 2>&1 || true)
    opt_out=$(java  $JVM_FLAGS -cp "$class_opt"  "$name" 2>&1 || true)
    if [ "$base_out" = "$opt_out" ]; then
        correctness="ok"
    else
        correctness="DIVERGES"
    fi

    if [ "$avg_base" -gt 0 ]; then
        speedup=$(( (avg_base - avg_opt) * 100 / avg_base ))
    else
        speedup=0
    fi

    printf "%-8s %10d %10d %9d%% %7d   %s\n" \
        "$name" "$avg_base" "$avg_opt" "$speedup" "$alloc_count" "$correctness"

    # Show the per-allocation verdicts inline for easy inspection.
    if [ -n "$results" ]; then
        printf "%s\n" "$results" | sed 's/^/           /'
    fi
done