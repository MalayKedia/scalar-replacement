# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project goal

A Soot-based (Java bytecode framework) static analyzer + transformer that performs **scalar replacement of aggregates**: identify heap allocations that do not escape their allocating method and are not mutated through calls, then rewrite the Jimple IR to replace each such object's fields with fresh locals so the allocation can be eliminated.

Planned extension (not yet implemented): partial escape analysis with speculative scalar replacement that reconstructs the object on paths where it actually escapes.

Academic references in `references/` (PLDI'24, Stadler thesis, and the 2544137 paper) describe the algorithms the implementation is based on.

## Layout

- `scalar-replacement/` — **the active codebase.** All work happens here.
- `PA3-malay/` — earlier handin (PA3). Kept for reference; do not edit unless the user explicitly asks.
- `testcases_balaji/TestN/` — primary test inputs. Each has `Test.java`, pre-compiled `.class` files, and an expected-output file named `Test` (the file, not the directory).
- `testcasesPA3/` — legacy test inputs for the old PA3 driver.
- `tcs_large/`, `transformed_output/` — ad hoc scratch dirs.
- `soot-4.6.0-jar-with-dependencies.jar` — the Soot jar used for everything. Keep it on the classpath.
- `todo` — plain-text list of known gaps (init-method handling, fields-of-other-objects, static fields). Consult when picking up work.

## Commands

All commands are run from the repo root.

```bash
# Run the analysis (+ transformation) on every testcase under testcases_balaji/,
# diff against each Test/Test expected-output file, report PASS/FAIL.
./run_tests.sh

# Run on specific testcases only (names are directory names).
./run_tests.sh Test1 Test7

# Benchmark one testcase: emits baseline (no-transform) and optimized class/Jimple
# into TestN/benchmark_baseline and TestN/benchmark_optimized, diffs the Jimple,
# and times both under `java -Xint` to defeat the JIT's own escape analysis.
./benchmark.sh testcases_balaji/Test1
```

Manual compile + run (what the scripts wrap):

```bash
javac -d scalar-replacement/build -cp soot-4.6.0-jar-with-dependencies.jar scalar-replacement/*.java

# Analysis only, prints scalar-replaceable allocation sites:
java -cp scalar-replacement/build:soot-4.6.0-jar-with-dependencies.jar PA3 testcases_balaji/Test1

# Analysis + transformation, emits class or Jimple to <outputDir>:
java -cp scalar-replacement/build:soot-4.6.0-jar-with-dependencies.jar \
     SootRunner <classPath> <outputDir> [--no-transform] [--format c|J]
```

Notes:
- `run_tests.sh` filters Soot's own stderr and lines starting with `Soot ` from stdout before diffing — don't print lines with that prefix from analyzer code.
- Expected output for a testcase lives at `testcases_balaji/TestN/Test` (no extension). Missing file → the script prints `RAN (no expected output)` and the raw output; this is not a failure.
- Benchmarks use `-Xint` deliberately. Without it, HotSpot's built-in escape analysis masks the effect of our transformation.

## Architecture

Pipeline: **Soot whole-program mode → `wjtp.dfa` scene transformer → per-method intra-procedural forward dataflow → inter-procedural summary table → IR rewrite.**

### Driver (`AnalysisTransformer` — `SceneTransformer`)

- Registered as the `wjtp.dfa` transform. There is exactly one entry point (`-main-class Test`); the driver asserts this.
- Walks the call graph in **reverse topological order** (callees before callers) by recursion. Assumes no call-graph cycles (no recursion).
- For each method: runs `PointerAnalysis`, stores the resulting `MethodSummary` so callers can query it, collects scalar-replacement results keyed by source line (printed in sorted order), and — unless `enableTransformation` is false — rewrites the method body in place.

### Intra-procedural analysis (`PointerAnalysis` — `ForwardFlowAnalysis<Unit, AnalysisState>`)

At the fixpoint, for every program point we know:
- which abstract objects each local may point to (`stack`),
- the abstract heap as `(base, field) → set<object>` (`heap`),
- which objects have escaped (`escaped`),
- which objects have been modified, with a subset tracking "modified through a call" (`modified`, `modifiedInCalls`),
- which locals are definitely initialized on all reaching paths (`initialized`) — used to pick strong vs. weak updates on field stores,
- source-line call sites each object flowed through (`callSites`, printed as part of the result).

After fixpoint the analysis exposes:
- `getScalarReplacementResults()` — allocation sites that are scalar-replaceable.
- `computeSummary()` — `MethodSummary` for the caller (which params escape, which have fields written directly, which have reachable-field writes, and per-param call sites).
- `performScalarReplacement()` — IR rewrite.

Stability trick: allocation sites, per-unit param placeholders, parameter placeholders, and lazy field-loads from params are **interned in maps keyed by Jimple `Unit`/index/`HeapKey`** so the same unit always yields the same abstract object across iterations. The analysis will not converge if this is broken.

### Heap abstraction (`AbstractObject`, `HeapKey`)

- `AllocObject` — identified by its allocation `Unit`. Equality = same site.
- `ParamObject` — placeholder for an unknown external object (method parameter on entry, return value, static-field load, lazy field-of-param). Each instance has a unique id so unrelated unknowns don't merge.
- `AbstractObject.NULL` — singleton for the null reference.
- `HeapKey(base, field)`. A null `field` means "array element" — indices are not tracked, so all elements of one array share one slot.

### Inter-procedural summaries (`MethodSummary`)

Parameter indexing is uniform across static and instance methods: for instance methods `0 = this`, `1…` = explicit params; for static methods `0…` = explicit params. Summaries record which param indices may escape, which have direct field writes, which have writes reachable through their heap descendants, and per-index call-site line numbers.

### Transformation (Soot option pitfall)

`SootRunner` disables `jb.ulp` and `jb.lp` (unused- and regular-local packers). Don't re-enable them: the scalar-replacement rewrite introduces locals of types that differ from the parameter slots they'd otherwise be packed into, producing StackMapTable conflicts that downstream decompilers (and some verifiers) reject.

## Known gaps

See `todo`. Current known-incorrect cases that should block marking an allocation scalar-replaceable:
- objects whose class has a non-empty `<init>`,
- objects stored as a field of another object,
- objects passed to (or whose class owns) static fields,
- classes with static fields — only non-static fields should become locals; static access must stay as `getstatic`/`putstatic`.
