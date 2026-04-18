#!/usr/bin/env bash
#
# Benchmark: run a test case with and without scalar replacement,
# compare execution times.
#
# Usage:  ./benchmark.sh <TestDir>
#   e.g.  ./benchmark.sh testcases_balaji/Test1
#
# Outputs:
#   benchmark_baseline/   — .class and .jimple files WITHOUT transformation
#   benchmark_optimized/  — .class and .jimple files WITH transformation

set -euo pipefail

if [ $# -lt 1 ]; then
    echo "Usage: $0 <TestDir>"
    echo "  e.g. $0 testcases_balaji/Test1"
    exit 1
fi

PROJECT_DIR="$(cd "$(dirname "$0")" && pwd)"
SRC_DIR="$PROJECT_DIR/scalar-replacement"
SOOT_JAR="$PROJECT_DIR/soot-4.6.0-jar-with-dependencies.jar"
BUILD_DIR="$SRC_DIR/build"
TESTCASE_DIR="$PROJECT_DIR/$1"

BASELINE_DIR="$PROJECT_DIR/benchmark_baseline"
OPTIMIZED_DIR="$PROJECT_DIR/benchmark_optimized"

ITERATIONS=5
# Use -Xint (interpreter only) so the JVM's own JIT escape analysis
# does not re-optimize the baseline away, masking our transformation.
JVM_FLAGS="-Xint"

# ── Step 1: Compile analysis ────────────────────────────────────
echo "=== Compiling analysis ==="
mkdir -p "$BUILD_DIR"
javac -d "$BUILD_DIR" -cp "$SOOT_JAR" "$SRC_DIR"/*.java
echo ""

# ── Step 2: Compile test case if needed ─────────────────────────
for f in "$TESTCASE_DIR"/*.java; do
    [ -f "$f" ] || continue
    echo "=== Compiling $f ==="
    javac "$f" 2>/dev/null || true
done
echo ""

# ── Step 3: Clean output dirs ──────────────────────────────────
rm -rf "$BASELINE_DIR" "$OPTIMIZED_DIR"
mkdir -p "$BASELINE_DIR" "$OPTIMIZED_DIR"

# ── Step 4: Soot pass — baseline (no transformation) ───────────
#    Run twice: once for class files, once for Jimple, into the same dir.
echo "=== Running Soot (baseline, no transformation) ==="
java -cp "$BUILD_DIR:$SOOT_JAR" SootRunner "$TESTCASE_DIR" "$BASELINE_DIR" --no-transform --format c 2>/dev/null | grep -v "^Soot \|^O[0-9]" || true
java -cp "$BUILD_DIR:$SOOT_JAR" SootRunner "$TESTCASE_DIR" "$BASELINE_DIR" --no-transform --format J 2>/dev/null | grep -v "^Soot \|^O[0-9]" || true
echo "  Output in: $BASELINE_DIR"
ls "$BASELINE_DIR"/ | sed 's/^/    /'
echo ""

# ── Step 5: Soot pass — optimized (with scalar replacement) ────
echo "=== Running Soot (optimized, with scalar replacement) ==="
java -cp "$BUILD_DIR:$SOOT_JAR" SootRunner "$TESTCASE_DIR" "$OPTIMIZED_DIR" --format c 2>/dev/null | grep -v "^Soot \|^O[0-9]" || true
java -cp "$BUILD_DIR:$SOOT_JAR" SootRunner "$TESTCASE_DIR" "$OPTIMIZED_DIR" --format J 2>/dev/null | grep -v "^Soot \|^O[0-9]" || true
echo "  Output in: $OPTIMIZED_DIR"
ls "$OPTIMIZED_DIR"/ | sed 's/^/    /'
echo ""

# ── Step 6: Show Jimple diff ────────────────────────────────────
echo "=== Jimple diff (baseline vs optimized) for Test class ==="
if diff -q "$BASELINE_DIR/Test.class" "$OPTIMIZED_DIR/Test.class" > /dev/null 2>&1; then
    echo "  No difference in Test.class (test may not have Y[] objects)"
else
    echo "  Test.class files differ — transformation applied!"
fi
echo ""
echo "--- Test.jimple diff ---"
diff --color=auto "$BASELINE_DIR/Test.jimple" "$OPTIMIZED_DIR/Test.jimple" || true
echo ""

# ── Step 7: Benchmark execution ─────────────────────────────────
echo "=== Benchmarking ($ITERATIONS iterations each) ==="
echo ""

# Warmup + time baseline
echo "--- Baseline (unoptimized) ---"
java $JVM_FLAGS -cp "$BASELINE_DIR" Test > /dev/null 2>&1 || true

baseline_total=0
for i in $(seq 1 $ITERATIONS); do
    start_ns=$(date +%s%N)
    java $JVM_FLAGS -cp "$BASELINE_DIR" Test > /dev/null 2>&1 || true
    end_ns=$(date +%s%N)
    elapsed_ms=$(( (end_ns - start_ns) / 1000000 ))
    baseline_total=$((baseline_total + elapsed_ms))
    printf "  Run %d: %d ms\n" "$i" "$elapsed_ms"
done
baseline_avg=$((baseline_total / ITERATIONS))
echo "  Average: ${baseline_avg} ms"
echo ""

# Warmup + time optimized
echo "--- Optimized (scalar replacement) ---"
java $JVM_FLAGS -cp "$OPTIMIZED_DIR" Test > /dev/null 2>&1 || true

optimized_total=0
for i in $(seq 1 $ITERATIONS); do
    start_ns=$(date +%s%N)
    java $JVM_FLAGS -cp "$OPTIMIZED_DIR" Test > /dev/null 2>&1 || true
    end_ns=$(date +%s%N)
    elapsed_ms=$(( (end_ns - start_ns) / 1000000 ))
    optimized_total=$((optimized_total + elapsed_ms))
    printf "  Run %d: %d ms\n" "$i" "$elapsed_ms"
done
optimized_avg=$((optimized_total / ITERATIONS))
echo "  Average: ${optimized_avg} ms"
echo ""

# ── Step 8: Summary ─────────────────────────────────────────────
echo "=== Summary ==="
echo "  Baseline avg:  ${baseline_avg} ms"
echo "  Optimized avg: ${optimized_avg} ms"
if [ "$baseline_avg" -gt 0 ]; then
    diff_ms=$((baseline_avg - optimized_avg))
    echo "  Difference:    ${diff_ms} ms"
    pct=$((diff_ms * 100 / baseline_avg))
    echo "  Speedup:       ${pct}%"
else
    echo "  (baseline too fast to measure meaningfully)"
fi
