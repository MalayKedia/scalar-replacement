#!/usr/bin/env bash
#
# PA4 entry-point script.
#
# 1. Cleans build + per-testcase class directories.
# 2. Compiles the optimizer: javac -cp <soot.jar> src/Main.java
#    (javac implicitly compiles the rest of src/ as dependencies.)
# 3. For each tests/TestN.java:
#      a. Compiles the testcase into out/TestN/base/.
#      b. Invokes Main to emit optimized .class files into out/TestN/opt/.
#      c. Runs both base and opt under -Xint, averaging wall-clock time
#         over several iterations.
#      d. Reports base ms, opt ms, speedup %, and the per-allocation
#         Y/P/L breakdown from our analyzer.
#
# Y = pure scalar replacement (no materialization anywhere)
# P = partial escape, materialize in current method body
# L = partial escape, materialize inside specialized callee body

set -eu
cd "$(dirname "$0")"

# ---------------------------------------------------------------
# Configuration
# ---------------------------------------------------------------
SOOT_JAR="soot-4.6.0-jar-with-dependencies.jar"
SRC_DIR="src"
BUILD_DIR="build"
TESTS_DIR="tests"
OUT_ROOT="out"
ITERATIONS="${ITERATIONS:-5}"
JVM_FLAGS="-Xint"

if [ ! -f "$SOOT_JAR" ]; then
    echo "error: Soot jar not found at $SOOT_JAR"
    exit 1
fi

# ---------------------------------------------------------------
# Step 1: Clean everything
# ---------------------------------------------------------------
rm -rf "$BUILD_DIR" "$OUT_ROOT"
find "$SRC_DIR" "$TESTS_DIR" -name "*.class" -delete 2>/dev/null || true

# ---------------------------------------------------------------
# Step 2: Compile the optimizer
# ---------------------------------------------------------------
echo "== Compiling optimizer =="
mkdir -p "$BUILD_DIR"
# -sourcepath lets javac pick up the other src/*.java files that Main.java references.
javac -cp "$SOOT_JAR" -sourcepath "$SRC_DIR" -d "$BUILD_DIR" "$SRC_DIR"/Main.java

# ---------------------------------------------------------------
# Step 3: Run each testcase
# ---------------------------------------------------------------
mkdir -p "$OUT_ROOT"

printf "\n%-8s %10s %10s %10s %7s %8s\n" \
    "Test" "base(ms)" "opt(ms)" "speedup" "allocs" "Y/P/L"
echo "-----------------------------------------------------------------"

for src in "$TESTS_DIR"/Test*.java; do
    [ -f "$src" ] || continue
    name=$(basename "$src" .java)
    base_dir="$OUT_ROOT/$name/base"
    opt_dir="$OUT_ROOT/$name/opt"
    mkdir -p "$base_dir" "$opt_dir"

    # a. Compile testcase
    if ! javac -d "$base_dir" "$src" 2>/dev/null; then
        printf "%-8s  compile FAIL\n" "$name"
        continue
    fi

    # b. Run the optimizer, capture Y/P/L lines
    results=$(java -cp "$BUILD_DIR:$SOOT_JAR" Main "$name" "$base_dir" "$opt_dir" \
                --format c 2>/dev/null | grep -E "^O[0-9]+ = [YPL]" || true)
    y=$(printf "%s\n" "$results" | grep -c "= Y\[" || true)
    p=$(printf "%s\n" "$results" | grep -c "= P\[" || true)
    l=$(printf "%s\n" "$results" | grep -c "= L\[" || true)
    total=$((y + p + l))

    if [ ! -f "$opt_dir/$name.class" ]; then
        printf "%-8s  transform FAIL (no $name.class emitted)\n" "$name"
        continue
    fi

    # c. Time baseline (one warm-up run + ITERATIONS timed runs)
    java $JVM_FLAGS -cp "$base_dir" "$name" > /dev/null 2>&1 || true
    total_base=0
    for i in $(seq 1 "$ITERATIONS"); do
        t1=$(date +%s%N)
        java $JVM_FLAGS -cp "$base_dir" "$name" > /dev/null 2>&1 || true
        t2=$(date +%s%N)
        total_base=$((total_base + (t2 - t1) / 1000000))
    done
    avg_base=$((total_base / ITERATIONS))

    # Time optimized
    java $JVM_FLAGS -cp "$opt_dir" "$name" > /dev/null 2>&1 || true
    total_opt=0
    for i in $(seq 1 "$ITERATIONS"); do
        t1=$(date +%s%N)
        java $JVM_FLAGS -cp "$opt_dir" "$name" > /dev/null 2>&1 || true
        t2=$(date +%s%N)
        total_opt=$((total_opt + (t2 - t1) / 1000000))
    done
    avg_opt=$((total_opt / ITERATIONS))

    # d. Correctness check — base vs opt output must match
    base_out=$(java $JVM_FLAGS -cp "$base_dir" "$name" 2>&1 || true)
    opt_out=$(java $JVM_FLAGS -cp "$opt_dir" "$name" 2>&1 || true)
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

    printf "%-8s %10d %10d %9d%% %7d %2d/%d/%d   [%s]\n" \
        "$name" "$avg_base" "$avg_opt" "$speedup" "$total" "$y" "$p" "$l" "$correctness"
done

echo
echo "Output .class files: $OUT_ROOT/<test>/opt/"
