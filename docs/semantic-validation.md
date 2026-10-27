# Semantic regression testing

Bytecode verification checks JVM structure; it does not establish equivalent behavior. The core regression suite runs independently loaded original and transformed fixtures with the same operation sequences, comparing return values, exception types/messages, and state after every operation. Independent expected states catch mistakes shared by both executions. Original application classes are absent from the transformed loader.

Run on JDK 21. The script adds `-Xverify:all` to the test JVM; `--full` runs the entire core and CLI suites:

```sh
python3 scripts/verify-semantic-regressions.py
python3 scripts/verify-semantic-regressions.py --full
```

`StatefulDifferentialTest` tests arithmetic, light control flow, call indirection/prelink generation, strings, numbers, and renaming separately, then together. Each configuration uses seeds 0, 1, 20261022, and `Long.MIN_VALUE`. The combined configuration uses strong protection with explicit light flow, constant fields, package flattening, generic signature stripping, four arithmetic operations per method, no VM selection, and structural budgets of 10000 input instructions, 256 input branches, 1024 added instructions, 300% growth, and 60000 output code bytes. These are consumer configuration values, not consumer-specific engine behavior. The fixture is independent of Bukkit and game rules; the generic platform is used.

The operation trace covers initialization, start, zero progress, negative input, boundary-minus-one, exact end boundary, rejected operations after end, and integer overflow that must not be interpreted as unbounded arithmetic. It checks ordered operand side effects, int/long arithmetic, negative values, masked shift distances, division by zero, caught/rethrown exceptions and finally effects. Each configuration must actually apply its requested transformation; the combined configuration must apply all ordinary passes.

`KotlinStatefulDifferentialTest` combines those passes on compiled Kotlin with interface dispatch and default arguments, private defaults with value-class parameters, data class copy/destructuring, enum names, delegated lazy initialization, captured lambdas, and callable references. Only the two test observation methods are explicitly kept. Other compiler contracts use ordinary engine constraints. Expected snapshots distinguish start from end and check exceptional updates.

## Initialization invariants

`InitializationSemanticsTest` reproduces two failures that valid bytecode can conceal:

- A superclass initializer calls a subclass method that reads a `ConstantValue` field using `GETSTATIC`. JVM constant preparation precedes supertype initialization. Moving the value to the subclass's `<clinit>` made the read return zero instead of seven.
- A superclass initializer calls a subclass method containing a string literal. The generated string cache has not yet been initialized. Previously the method threw `NullPointerException`; it must return the literal. Both ordinary and oversized encrypted payload paths are exercised.

Constant extraction now retains attributes for classes with a non-`Object` superclass or any interfaces. This deliberately conservative boundary includes external supertypes and interfaces without default methods: proving their complete initialization behavior is outside this pass. The report exposes `initializationSkippedClasses`, and existing class initializers have skip reason `constant-preparation-before-supertype-initialization`. Literal transforms in ordinary methods remain enabled. Explicitly kept external API constants also remain excluded as before.

String accessors decode and intern without caching when called before cache setup. They use the existing atomic cache after setup. Oversized literal accessors take the same uncached path when necessary. This adds a null check; runtime performance is not established by semantic tests.

## Remaining boundaries

| Area | Contract and remaining risks |
| --- | --- |
| Integer expressions | Rewrites only modular int/long add, subtract and XOR; operand evaluation stays ordered. Floating-point arithmetic, shifts and division are not algebraically rewritten. Boundary tests also cover their interaction with number and flow passes. |
| Control flow | Light layout changes retain direct edges and ordered exception handlers. Existing exception-flow tests additionally exercise nested handlers, finally and monitors. A finite trace set does not cover every possible control-flow graph or schedule. |
| Calls | Constructors, class initializers, synthetic methods and unsupported invocation forms are excluded. Existing prelink tests check bounds and target initialization separately. Target exceptions are compared by type/message; exact stack traces, timing and linkage failures are not asserted equivalent. |
| Strings and constants | Interned identity and initialization reentry are tested. Extraction changes Java compile-time-constant API status where allowed; keep externally consumed constants. Allocation/OOM behavior and initialization deadlocks under all schedules are not covered. |
| Kotlin/JVM names | Metadata and compiler ABI constraints protect supported contracts; existing relocation tests include consumer compilation/reflection. Arbitrary dynamically constructed reflection names, serialization names, external configuration and unknown compiler/plugin metadata require explicit keep rules or rejection. |

## Artifact provenance and consumer validation

Record SHA-256 of the input, output, mapping and reports before testing. Record the tool build, settings and resolved dependency classpath. Do not infer common input from version labels or timestamps. Where deterministic replay is available, regenerate into a separate destination using the same tool, input, configuration and dependencies, then compare all four generated artifacts byte-for-byte. After an engine change, regenerate again; old output does not validate new code. Keep per-run hashes and logs outside committed source.

These fixtures establish only the exercised behavior on the tested JVM. They do not execute a live Paper match, scheduler, network events or real players, and cannot guarantee all consumer paths, concurrency, supported JVM/OS combinations or complete semantic equivalence. MockBukkit missing a CraftPlayer is an environment limitation, not evidence of a transformation defect.
