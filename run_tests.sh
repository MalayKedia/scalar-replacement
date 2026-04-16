#!/usr/bin/env bash
#
# Compile the analysis and run it on every testcase.
# Usage:  ./run_tests.sh               (run all)
#         ./run_tests.sh test3 test7    (run specific ones)
#
# If a matching expected-output file exists in ../testcasesPA3/,
# it diffs against it and reports PASS / FAIL.

set -euo pipefail

PROJECT_DIR="$(pwd)"
SRC_DIR="$PROJECT_DIR/scalar-replacement"
SOOT_JAR="$PROJECT_DIR/soot-4.6.0-jar-with-dependencies.jar"
TESTCASE_DIR="$PROJECT_DIR/testcases_balaji"
BUILD_DIR="$SRC_DIR/build"

# ── Compile ──────────────────────────────────────────────────────
mkdir -p "$BUILD_DIR"
echo "Compiling..."
javac -d "$BUILD_DIR" -cp "$SOOT_JAR" "$SRC_DIR"/*.java
echo ""

# ── Select testcases ─────────────────────────────────────────────
if [ $# -gt 0 ]; then
    tests=("$@")
else
    tests=()
    for d in "$TESTCASE_DIR"/*/; do
        tests+=("$(basename "$d")")
    done
fi

# ── Run ──────────────────────────────────────────────────────────
pass=0
fail=0
skip=0
total=${#tests[@]}

for tc in "${tests[@]}"; do
    printf "%-20s" "$tc"

    # Compile the testcase if needed
    if [ -f "$TESTCASE_DIR/$tc/Test.java" ] && [ ! -f "$TESTCASE_DIR/$tc/Test.class" ]; then
        javac "$TESTCASE_DIR/$tc/Test.java" 2>/dev/null || true
    fi

    # Run analysis; capture stdout, suppress Soot's stderr noise
    actual=$(cd "$PROJECT_DIR" && java -cp "$BUILD_DIR:$SOOT_JAR" PA3 "$TESTCASE_DIR/$tc" 2>/dev/null | grep -v "^Soot ") || true

    if [ -f $TESTCASE_DIR/$tc"/Test" ]; then
        expected=$(cat $TESTCASE_DIR/$tc"/Test")
        if [ "$actual" = "$expected" ]; then
            echo "PASS"
            ((pass++)) || true
        else
            echo "FAIL"
            diff --color=auto <(echo "$actual") <(echo "$expected") || true
            ((fail++)) || true
        fi
    else
        # No expected output — just print what we got
        echo "RAN   (no expected output)"
        echo "$actual" | sed 's/^/    /'
    fi
done

# ── Summary ──────────────────────────────────────────────────────
echo ""
echo "────────────────────────────────"
echo "Total: $total   Pass: $pass   Fail: $fail"
