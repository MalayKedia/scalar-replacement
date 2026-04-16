#!/usr/bin/env bash
#
# Benchmark: run a test case with and without scalar replacement,
# compare execution times.
#
# Usage:  ./benchmark.sh <TestDir>
#   e.g.  ./benchmark.sh testcases_balaji/Test1
#
# What it does:
#   1. Compiles the analysis + PA3Benchmark entry point.
#   2. Compiles the test case's .java if needed.
#   3. Runs Soot WITHOUT transformation  → baseline .class files.
#   4. Runs Soot WITH transformation     → optimized .class files.
#   5. Executes both and reports wall-clock times.

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

BASELINE_DIR=$(mktemp -d)
OPTIMIZED_DIR=$(mktemp -d)
trap 'rm -rf "$BASELINE_DIR" "$OPTIMIZED_DIR"' EXIT

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
if [ -f "$TESTCASE_DIR/Test.java" ]; then
    echo "=== Compiling test case ==="
    javac "$TESTCASE_DIR/Test.java" 2>/dev/null || true
    echo ""
fi

# ── Step 3: Soot pass — baseline (no transformation) ───────────
echo "=== Running Soot (baseline, no transformation) ==="
java -cp "$BUILD_DIR:$SOOT_JAR" PA3Benchmark "$TESTCASE_DIR" "$BASELINE_DIR" --no-transform 2>/dev/null | grep -v "^Soot \|^O[0-9]" || true
echo "  Output in: $BASELINE_DIR"
echo ""

# ── Step 4: Soot pass — optimized (with scalar replacement) ────
echo "=== Running Soot (optimized, with scalar replacement) ==="
java -cp "$BUILD_DIR:$SOOT_JAR" PA3Benchmark "$TESTCASE_DIR" "$OPTIMIZED_DIR" 2>/dev/null | grep -v "^Soot \|^O[0-9]" || true
echo "  Output in: $OPTIMIZED_DIR"
echo ""

# ── Step 5: Show Jimple diff ────────────────────────────────────
echo "=== Jimple diff (baseline vs optimized) for Test class ==="

echo "(Checking that optimized class files differ from baseline)"
if diff -q "$BASELINE_DIR/Test.class" "$OPTIMIZED_DIR/Test.class" > /dev/null 2>&1; then
    echo "  No difference in Test.class (test may not have Y[] objects)"
else
    echo "  Test.class files differ — transformation applied!"
fi
echo ""

# ── Step 6: Benchmark execution ─────────────────────────────────
echo "=== Benchmarking ($ITERATIONS iterations each) ==="
echo ""

# Warmup + time baseline
echo "--- Baseline (unoptimized) ---"
# Warmup
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
# Warmup
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

# ── Step 7: Summary ─────────────────────────────────────────────
echo "=== Summary ==="
echo "  Baseline avg:  ${baseline_avg} ms"
echo "  Optimized avg: ${optimized_avg} ms"
if [ "$baseline_avg" -gt 0 ]; then
    diff_ms=$((baseline_avg - optimized_avg))
    echo "  Difference:    ${diff_ms} ms"
    # Integer percentage
    pct=$((diff_ms * 100 / baseline_avg))
    echo "  Speedup:       ${pct}%"
else
    echo "  (baseline too fast to measure meaningfully)"
fi
