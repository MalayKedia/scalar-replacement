We write our code in Soot in Java framework.

We will start with code to identify scalar replacable objects by doing some sort of naive interprocedural analysis to identify objects which do not escape the lifetime of the allocator function, and are not modified inside the function calls in which it is passed as an argument.

Then, we want to use code transformations to achieve scalar replacement. 

After that, we want to extend our escape analysis to partial escape analysis, and try to do speculative scalar replacement, reconstructing the object if it actually escapes.

Current plan:

Revised plan with the two constraints: (a) specialize, never inline calls; (b) disqualify any allocation passed to a callee that
  writes its fields.

  Phase 0 — Setup

  Build call graph. Compute SCCs, reverse-topological order.

  Phase 1 — Per-method summaries (pessimistic)

  For methods in a non-singleton SCC: mark every param not good. Done.

  For singleton methods, run one intra-procedural forward pass with may-alias over param-origin tags. Produce per method:

  MethodSummary {
      goodParams:                   Set<Integer>
      escapingParams:               Set<Integer>
      identityUsedParams:           Set<Integer>
      forwardedBadParams:           Set<Integer>
      directlyModifiedFields:       Map<Integer, Set<SootField>>   // param.f = v
      calleeModifiedFields:         Map<Integer, Set<SootField>>   // via forwarded good call
      readFields:                   Map<Integer, Set<SootField>>   // including transitive
  }

  modifiedFields = directly ∪ callee. A param is good iff it's not in escapingParams / identityUsedParams / forwardedBadParams. This
  classification is independent of whether the param's fields get written — goodness is about what the function does with the reference,
   not with its contents.

  Virtual calls: all CHA targets must agree for the call to count as good. Phantom methods are never good.

  Phase 2 — Per-method scalar-replaceability

  For every allocation o = new X():

  1. Non-<init> uses of o: the usual disqualifier list (escape, identity, stored into another object's field, thread/lambda capture).
  2. Every non-<init> invoke passing o: the corresponding callee param must be good and callee.modifiedFields[that_param] == ∅. Virtual
  → must hold for every target. This is the hard rule that enforces "I don't want to write values back."
  3. The <init> chain: walk X.<init> → super chain → Object.<init>. For every constructor c in the chain:
    - c.goodParams[0] (i.e., this is good inside c).
    - c.calleeModifiedFields[0] == ∅ — i.e., <init> writes fields only via direct this.f = v stores, never through helper calls. Helper
  calls from <init> are allowed but must themselves not mutate this's fields (because we'll specialize them, and specialized versions
  can't write back).

  If all checks pass, record the allocation with:
  - the <init> chain,
  - the set of fields read/written (including transitively via specialized callees — comes from the readFields/modifiedFields of each
  call's target, intersected with "this object's fields"),
  - the list of non-<init> call sites to rewrite.

  Phase 3 — Transformation

  For each replaceable allocation:

  1. Inline the <init> chain. Walk outermost-in; for each constructor body, substitute this.f = v with local_f = v and map formal params
   to supplied actuals. super.<init> calls are replaced by the next iteration's body. Object.<init> → drop. Any helper call in the body
  stays in place, now with this referring to the soon-to-be-deleted o.
  2. Delete the new X and the top-level specialinvoke <init>.
  3. Rewrite direct field accesses on o and its aliases to the scalar locals.
  4. Specialize each callee of a remaining invoke that receives o. For call g(..., o, ...) (including o.g(...) where the receiver is o):
    - Form a key (g, paramsToScalarize).
    - If not already synthesized, create a fresh static method g$scalar_<key> with:
        - scalar-replaced ref params replaced by one primitive/ref param per field in g.readFields[that_idx] ∪
  g.modifiedFields[that_idx] (in a canonical order),
      - same other params and return type.
    - Clone g's body; rewrite accesses on the scalarized param to the corresponding new scalar params; recursively specialize any onward
   calls it makes that forward the scalarized ref.
    - Replace the original call with an invoke of g$scalar_<key>, passing the scalar locals in the canonical order.
  5. Virtual calls on o always devirtualize to the exact class X (concrete type known from the allocation site), so the specialization
  target is unambiguous even for virtual invokes.

  Because of the Phase 2 rule, no specialized method ever writes to a scalarized param's fields, so the signature has no out-params and
  the return type is unchanged. Clean.

  Recursive specialization isn't a concern: any function in a non-singleton SCC had all params marked not-good in Phase 1, so it's never
   reached by this pipeline.

  Summary of changed data vs. the previous plan

  - Added directlyModifiedFields and calleeModifiedFields (split of modifiedFields) so Phase 2 can enforce the <init> helper rule.
  - Phase 2 now has a hard "callee writes no fields" check for every non-<init> call on the allocation.
  - Phase 3 step 5 is specialize, not inline. Signatures only widen — never grow return type — because writes-back can't happen.

  Implementation order (unchanged)

  1. Gut PointerAnalysis.java, rebuild as the Phase-1 summary pass.
  2. Drive it via SCC-reverse-topo from AnalysisTransformer.
  3. Validate verdicts against testcases_balaji/*/Test with no IR mutation.
  4. Add Phase 2.
  5. Add Phase 3 constructor-inline + field-local rewrite first; validate with benchmark.sh.
  6. Add specialization last.

  Ready for me to start on step 1?

