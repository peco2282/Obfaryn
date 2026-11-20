# Semantics that translations must preserve

- `must` and `required` express a requirement; translate them as `必要があります`,
  `しなければなりません`, or another equally strong form.
- `must not`, `never`, and `forbidden` express prohibition; never weaken them into a
  recommendation.
- Preserve nullable/non-null distinctions and the exact condition under which
  `null`, an exception, fallback behavior, or rollback occurs.
- Thread safety, mutability, ownership, ordering, and lifecycle limitations must
  retain their original polarity and scope.
- VM/virtualization selection is annotation- or explicit-pattern-driven. A
  protection profile does not automatically select methods for virtualization.
- A body-transformation budget is structural. It is not a runtime-performance
  prediction or guarantee.
- Budget rollback is per class and pass and includes associated generated fields,
  initializers, and helper methods.
- Prelink is explicit and bounded by site count. It does not execute target methods
  and does not promise to remove JVM linkage or JIT costs.
- Artifact-path validation prevents known input/output identity conflicts but is not
  filesystem locking or a multi-artifact transaction.
- Do not turn implementation observations into compatibility, performance, or
  security guarantees.

