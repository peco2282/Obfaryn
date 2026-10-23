# Feature policy, structural budgets and explicit prelinking

These features apply to JVM programs independently of Paper. Method identities use original JVM internal names: `com/example/Physics.step(I)I`. Application roots are required for call indirection and integer arithmetic. Existing `@Virtualize` selection, exclusions and its analysis report remain separate; ordinary body budgets do not predict or bound interpreter execution time.

## Per-feature annotations

```kotlin
import com.peco2282.obfaryn.annotations.*

@Protection(
  controlFlow = FlowProtection.LIGHT,
  callIndirection = ProtectionMode.DISABLED,
  integerArithmetic = ProtectionMode.DISABLED,
)
class Physics {
  @Protection(stringEncryption = ProtectionMode.ENABLED)
  @PerformanceBudget(maxAddedInstructions = 64, maxGrowthPercent = 175)
  fun step(n: Int): Int = n + 17
}
```

`Protection` has independent `stringEncryption`, `numberObfuscation`, `callIndirection`, `integerArithmetic` and `constantFields` permissions (`INHERIT`, `ENABLED`, `DISABLED`). `controlFlow` is `INHERIT`, `NONE`, `LIGHT` or `DISPATCH`. A class supplies defaults; a method overrides individual properties. `ENABLED` permits an already enabled pass; it does not register a globally disabled pass. A method's `constantFields` setting is meaningful on a class initializer, rather than on arbitrary methods.

Precedence, from strongest to weakest:

1. Class exclusions and existing `NoObfuscation`, `RenameOnly`, `HotPath` body policy.
2. Explicit method `Protection` properties.
3. Explicit class `Protection` properties.
4. Matching API rules (last matching rule wins per feature).
5. Run defaults.

Existing method-level `@Virtualize` behavior under class-level `@HotPath`/`@RenameOnly` remains as before. A new `Protection` annotation cannot bypass those restrictions. `KeepName` only preserves names. No arbitrary names such as `tick` or `onTick` influence core policy.

For source-independent configuration:

```kotlin
import com.peco2282.obfaryn.api.protection.*

val rules = listOf(ProtectionRule(
  methodPattern = "com/example/Physics\\.step\\(I\\)I",
  features = mapOf(BodyFeature.CALLS to false),
  controlFlow = ControlFlowMode.LIGHT,
  budget = TransformationBudget(maxAddedInstructions = 64),
))
```

Rules match ORIGINAL identities even after renaming/relocation. A rule budget replaces the run budget; class/method budget annotations then inherit or override its individual properties. Annotation values of `-1` inherit. Other negative values fail before output.

## Gradle and CLI configuration

Gradle Kotlin and Groovy builds can group settings by feature:

```kotlin
obfaryn {
  applicationPackages += "com.example"
  protection = "strong"
  controlFlow { mode = "light" }
  calls { enabled = true; prelink = true }
  arithmetic { enabled = true; maxOperationsPerMethod = 4 }
  reports {
    methods = "build/methods.json"
    virtualization = "build/virtualization.json"
  }
  rules {
    method("com/example/Physics\\.step\\(I\\)I") {
      calls = false
      integerArithmetic = false
      controlFlow = "none"
      performance { maxAddedInstructions = 64 }
    }
  }
}
```

`controlFlow.mode` accepts `inherit`, `none`, `light` or `dispatch`, ignoring case.
An explicit mode overrides the legacy control-flow flags and `protection = "strong"`.
`none` disables the entire control-flow pass, including per-method requests. `inherit`
uses the existing flags and protection level. The mode never selects virtualization.
Other nested properties share their values with the existing flat settings, so the last
assignment wins. `calls.prelink = true` requires `calls.enabled = true`.

Rule properties `strings`, `numbers`, `calls`, `integerArithmetic` and `constantFields`
are nullable booleans: omitted/null inherits. `controlFlow` uses the modes above.
Rules retain declaration order and the policy precedence described earlier. A rule's
`performance` block replaces the run budget; unspecified limits in that block use
`TransformationBudget` defaults, rather than inheriting run limits. Existing
`protectionRules` API values and this DSL share one rule list.

Report paths and additional Gradle `classpath` entries resolve relative to the project.
`reports.methods` aliases `transformationReport`; `reports.virtualization` aliases
`virtualize { report = ... }` and does not select or analyze methods by itself.
The strong profile still supplies its default virtualization report when no path is set.

CLI example (quote regexes so the shell does not interpret them):

```sh
obfaryn app.jar --platform generic --application-package com.example \
  --protection strong --control-flow-mode light \
  --call-indirection --prelink-calls --integer-arithmetic --arithmetic-operations 4 \
  --disable-feature 'calls=com/example/Physics\.step\(I\)I' \
  --disable-feature 'integer-arithmetic=com/example/Physics\.step\(I\)I' \
  --max-added-instructions 256 --transformation-report methods.json
```

`--disable-feature FEATURE=REGEX` is repeatable and creates ordinary feature-policy
rules using original method identities. Features are `strings`, `numbers`, `control-flow`,
`calls`, `integer-arithmetic` and `constant-fields`. Only the first `=` separates the
feature and regex; a regex may contain `=`. Existing annotations retain their higher
priority. To disable the whole control-flow pass even under `strong`, use
`--control-flow-mode none`. Explicit mode selection takes precedence over
`--control-flow` and `--lightweight-control-flow`, regardless of argument order.
`--no-rename-classes`, `--no-rename-methods` and `--no-rename-fields` control renaming
independently; `--no-rename` disables all three.

For repeatable invocations, put one argument per line in an `@args.txt` file, quoting
arguments that contain spaces. Use JVM internal names (`/`) in method regexes and
package names (`.`) in application roots. Structural limits are counts, not time limits.

## Structural budgets

```kotlin
obfaryn {
  applicationPackages += "com.example"
  protection = "strong"
  controlFlow { mode = "light" }
  calls { enabled = true; prelink = true }
  arithmetic { enabled = true; maxOperationsPerMethod = 4 }
  reports { methods = "build/protection-decisions.json" }
  performance {
    maxInputInstructions = 2000
    maxInputBranches = 128
    maxAddedInstructions = 256
    maxGrowthPercent = 175
    growthAllowanceInstructions = 64
    maxOutputCodeBytes = 60000
  }
}
```

The core API exposes `transformationBudget`, `protectionRules`, `integerArithmetic`, `maxArithmeticOperationsPerMethod`, `prelinkCalls` and `transformationReport` on `ObfuscationConfig`. `TransformationBudget.latencySensitive()` supplies the limits above. It is a starting point for measurement, not a latency guarantee.

- Input instruction and branch-instruction limits are checked before each pass. A switch counts as one branch instruction.
- Added instructions and percentage growth are cumulative against the original method body, across ordinary passes. `maxAddedInstructions = 0` is valid and permits no added instructions.
- The percentage check allows `outputInstructions <= max(originalInstructions * maxGrowthPercent / 100, originalInstructions + growthAllowanceInstructions)`. The allowance defaults to 64 instructions so small Kotlin lambdas and compiler-generated methods can still receive encryption. It is cumulative, never resets per pass, and is used only when `maxGrowthPercent` is set. No lambda detection or naming heuristics are used. Added-instruction, output-code and class-file limits still apply independently.
- Set `growthAllowanceInstructions = 0` for the previous strict ratio. For example, a four-instruction body at 175% permits 68 output instructions by default, or seven with allowance zero. The option also works in rule-level `performance` blocks and `@PerformanceBudget(growthAllowanceInstructions = ...)`; annotation `-1` inherits while `0` explicitly selects a strict ratio.
- Output code size uses ASM's conservative maximum encoded size, including possible branch widening. It may reject a method whose actual encoding would be smaller.
- Generated synthetic helpers, including methods in separately injected helper classes, are checked against their resolved output code limits but do not have a meaningful original-body growth ratio. A helper class uses its own feature-policy identity and annotations with the run budget as its default. New initializers also respect the added-instruction limit.
- The engine always checks class-file code/constant-pool limits for participating passes. Optional structural limits default to unlimited; the class-file limit remains mandatory.

Candidates commit **per class and pass**. If one changed method exceeds an output budget, that entire class's changes for that pass are rejected, including generated fields, initializers and helper classes. Changes committed by previous passes remain. This deliberately conservative behavior prevents partially extracted constants or decoders with missing cache initialization. It can leave more code readable than a method-only rollback would.

String/number transformations, constant extraction, control flow, integer arithmetic and call indirection use this isolation. Global transformations such as renaming and virtualization have their own semantics. The global fallback that ensures the optional `ProtectionRuntime` API is available also remains outside class-local body budgets. Custom passes may opt in with `Transformer.isolatedBodyFeature`; the contract permits one candidate class, dependency lookup for reading and buffered class injection, but prohibits resource mutation, dependency mutation and class removal. `skipReason` supplies a pure, randomness-free diagnostic. `transformers { integerArithmetic(...); callIndirection(...) }` builds a list; register or supply that list explicitly as described in the main README.

## Integer expressions

`integerArithmetic = true` / `--integer-arithmetic` rewrites bounded int/long addition, subtraction and XOR with keyed modular identities. It preserves JVM overflow, left-to-right operand evaluation and side effects. It does not rewrite floating-point expressions, multiplication, division, remainder or shifts. Constructors, initializers and synthetic methods are excluded. There is no interpreter, boxing or added method invocation in the generated expression. Extra instructions can still affect execution and JIT decisions; the JIT may simplify them and an analyst can recover the identity.

## Explicit call preparation

Enable `prelinkCalls = true` / `--prelink-calls` together with call indirection. Applications that call the optional API can compile against the small, zero-dependency Java 8 module:

```kotlin
dependencies {
  compileOnly("com.peco2282.obfaryn:obfaryn-runtime:<your-version>")
}
```

The obfuscator embeds the API when enabled; the engine is not a runtime dependency. Generated helpers use `ClassValue` to avoid retaining child-loader caller classes in a parent-held global map.

```kotlin
import com.peco2282.obfaryn.runtime.ProtectionRuntime

// At an explicitly chosen startup point, outside latency-sensitive work:
var offset = 0
while (true) {
  val prepared = ProtectionRuntime.prelink(Physics::class.java, offset, 32)
  if (prepared == 0) break
  offset += prepared
}
```

`prelink(caller, startSite, maxSites)` returns the number of sites processed in that stable range. `prelink(caller, maxSites)` starts at zero. Repeated calls are safe but do not advance an implicit cursor. Arguments must be non-negative. A class without a generated entry returns zero.

This API **does not invoke call targets**. It loads their classes without initialization, resolves handles using the original caller's lookup, and caches constant call sites. Invoking the generated entry **does initialize the selected caller**; choose its lifecycle point explicitly. Resolution may fail if a dependency, member or access permission is unavailable, even for a path the application would not yet execute. Failures propagate; no silent success is reported. One resolved site can load dependencies and take substantial time: a site-count limit is not a time limit.

Preparation reduces decoding/lookup work in the first real invocation. The JVM still performs the actual `invokedynamic` linkage on first use and may compile code later. It does not guarantee zero first-use latency. Generated entries are chunked to avoid one oversized preparation method. Nothing automatically scans or initializes classes, and no Paper lifecycle method is intercepted.

## Decision reports

`--transformation-report PATH` writes schema version 1 JSON. `ObfuscationResult.transformerReports[*].methods` exposes the same method rows. Only executed passes appear; detailed rows cover the class-local ordinary body passes.

Each row contains original identity, `applied` / `skipped` / `rejected`, a reason, input/output executable instruction and branch-instruction counts, and conservative input/output code bytes. Reasons distinguish legacy annotations, individual feature restrictions, input limits, class rollback, unsupported shapes and generated helpers. Excluded classes are included as skipped rows. Methods in separately injected helper classes are included with reason `generated-helper-class`. Rejected helper rows have zero input and output size because those helpers are not committed. The report is optional and contains original names: do not distribute it with the protected JAR.

Before reading or transforming the input, core checks JAR, mapping (including its default destination), method report and virtualization report destinations against one another and against inputs, dependencies and integrity keys. CLI summary reports use the same file-identity check. Existing hard links and symbolic links, including aliases of future destinations, cannot bypass this check. A collision fails before any output is written. These checks do not make the separate artifact writes one transaction.

CLI counterparts: `--max-input-instructions`, `--max-input-branches`, `--max-added-instructions`, `--max-growth-percent`, `--growth-allowance-instructions`, `--max-output-code-bytes`, `--arithmetic-operations`.

## Repeatable verification

- `./gradlew test`: feature regression suites, including modular overflow, floating-point non-interference, operand side effects, policy inheritance, original-name rules, cumulative budgets, class-atomic rollback, bounded/concurrent preparation and Kotlin-style enum array cloning.
- `python3 scripts/compatibility/run.py`: clones pinned public commits from `scripts/compatibility/corpus.json`, builds upstream sources, transforms their JVM JARs and runs the same compiled tests against the transformed artifacts. Production output directories AND the original production JAR are removed from the replacement test classpath. Public API names are kept for these library projects. JVM verification is enabled. Upstream source algorithms are not edited. `--skip-build` is for an existing matching local build; do not use it for fresh CI validation.
- `python3 scripts/performance/run.py`: synthetic entity-loop comparison, independent JVM forks, alternating run order and a minimum timed warmup before resetting inputs for the measured phase. Records cold invocation, explicit preparation cost, warm p50/p95/p99/max, thread allocations and a bit-exact checksum. It includes a `HotPath` control group. Optional `--max-p99-ratio` and `--max-extra-bytes-per-tick` fail a local regression gate. Shared-runner timing is noisy; this is not a Minecraft TPS benchmark.

The GitHub workflow runs unit/integration tests on changes, public-project builds weekly/manually and the synthetic benchmark manually. Logs and JSON results are retained as artifacts. These checks strengthen release evidence; they do not establish that every JVM framework is compatible or that commercial readiness and unchanged runtime performance are guaranteed. Actual server startup, workload tails, memory/GC and deployment combinations still require representative testing.

API default hooks use JVM defaults with Kotlin `DefaultImpls` compatibility bridges. A regression test compiles a policy implementation against the old interface shape and loads it against the current API. See the [Kotlin compiler options](https://kotlinlang.org/docs/gradle-compiler-options.html).

The prelink embedding path is validated for classpath JARs. Named-module descriptors and `requires` directives are not rewritten; JPMS deployment needs separate validation. Static handle lookup initialization semantics are described by [MethodHandles.Lookup](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/lang/invoke/MethodHandles.Lookup.html#findStatic(java.lang.Class,java.lang.String,java.lang.invoke.MethodType)).
