# Repository Guidelines

## Project Structure & Module Organization

Obfaryn is an ASM-based obfuscator for Kotlin/JVM and Java JARs. Gradle modules separate responsibilities:

- `obfaryn-api`: platform and transformer interfaces.
- `obfaryn-annotations`: transformation policy annotations.
- `obfaryn-runtime`: optional Java 8 prelink API and dependency-free linkage templates embedded in output JARs.
- `obfaryn-core`: obfuscation engine and runtime helpers.
- `obfaryn-transformer`: standard transformations and builders.
- `obfaryn-cli`: Picocli command-line application.
- `obfaryn-gradle-plugin`: Gradle integration.
- `platforms/paper`: Paper platform provider and cache support.

Sources follow `src/main/kotlin` and, where needed, `src/main/java`. Tests live in `src/test/kotlin`; service registrations and fixtures use `src/main/resources` or `src/test/resources`. Shared dependency versions live in `gradle/libs.versions.toml`.

Feature policy, budgets and method reports live in `obfaryn-api/.../protection` and `obfaryn-core/.../protection`. Integer arithmetic and linkage generation live in `obfaryn-transformer/.../arithmetic` and `.../linkage`. Keep Paper-specific behavior in `platforms/paper`; consumer projects are validation inputs, not special cases in core.

## Build, Test, and Development Commands

Use JDK 21 and the checked-in Gradle wrapper (`gradlew.bat` on Windows).

- `./gradlew build`: compile, test, and assemble the project.
- `./gradlew test`: run the test suites.
- `./gradlew :obfaryn-core:test`: run engine tests only.
- `./gradlew :obfaryn-gradle-plugin:test`: run Gradle integration tests.
- `./gradlew :obfaryn-cli:run --args='--help'`: run the CLI locally.
- `./gradlew :obfaryn-cli:installDist`: generate the runnable distribution under `obfaryn-cli/build/install/obfaryn`.
- `python3 scripts/verify-cli-distribution.py`: after `installDist`, transform and execute a Java 8 fixture using only the installed CLI and output JAR.
- `python3 scripts/compatibility/run.py`: build pinned JOML/colormath sources and run their tests against transformed JARs. Use `--project joml` or `--project colormath` to narrow the run; `--skip-build` requires an existing matching upstream build.
- `python3 scripts/performance/run.py`: synthetic workload, three JVM forks by default, timed warmup, latency percentiles, allocations and result checks. Run separately from builds/tests for useful timing.

Preserve Java 8 bytecode compatibility for library and CLI modules; the Gradle plugin targets Java 17.

## Coding Style & Naming Conventions

Follow `.editorconfig`: UTF-8, LF endings, final newlines, and two-space indentation for `.kt` and `.kts` files. Batch scripts use CRLF. Use `UpperCamelCase` for types, `lowerCamelCase` for functions and properties, and packages under `com.peco2282.obfaryn`. Match surrounding code; no dedicated formatter or lint task is configured.

## Testing Guidelines

Use JUnit Jupiter; plugin integration tests also use Gradle TestKit. Name test classes/files `*Test` and use descriptive test methods. Add regression tests for transformation changes, checking both bytecode validity and runtime behavior where relevant. Use temporary directories for generated JARs. No numeric coverage threshold is configured.

For Gradle DSL changes, exercise Kotlin and Groovy through TestKit; nested rule objects use Gradle's `ObjectFactory` so Groovy closures configure the intended target. For CLI changes, cover both default and `obfuscate` invocations. Validate processed JARs with JVM verification and remapped consumer tests, excluding original application classes/JARs from the runtime classpath. Previously passing artifacts do not validate changed code.

## Transformation and Configuration Invariants

- VM selection stays annotation/explicit-pattern driven. Never infer execution frequency from names such as `tick` or `onTick`, and never expand VM selection through a protection profile.
- Explicit `controlFlowMode` overrides legacy flow flags and the `strong` default. `NONE` disables registration of that pass; a per-method permission cannot enable a globally absent pass.
- Resolve feature rules using original method identities. Existing body restrictions take priority, followed by method annotations, class annotations, matching rules and run defaults. Later matching rules override individual properties.
- Keep Gradle nested options and their flat aliases backed by the same values. Document any explicit-mode precedence separately. `transformers { ... }` creates a list; register passes through `pipeline` or supply that list to the host.
- Budgeted body passes commit per class and pass. Roll back fields, initializers and helper injections together. Respect cumulative growth, generated method code limits and class-file limits; oversize comparison must not prevent rollback.
- Report methods in injected helper classes too. Rejected new methods have zero committed output size. Global passes, including the fallback prelink API injection, have separate semantics from class-local body budgets.
- Prelink is explicit and bounded by site count, not elapsed time. It does not execute targets but can initialize the caller; do not add automatic lifecycle scans or claim to eliminate JVM linkage/JIT costs.
- Validate artifact destinations before writing. Share `core.io.ArtifactPaths` identity checks across core and CLI; cover hard links, symbolic links, future destinations and `..` after links. Do not overwrite inputs, dependencies, integrity keys or another artifact. These checks are not filesystem locking or a multi-artifact transaction.

## Implementation Status and Validation Scope

See [implementation status](docs/implementation-status.md) ([日本語](docs/implementation-status.ja.md)) and [protection controls](docs/protection.md) ([日本語](docs/protection.ja.md)). The six requested features are implemented; do not treat historical handoff failures as current TODOs without reproducing them against current artifacts.

Separate implementation work from remaining release/runtime validation. Local JDK 21 tests, classpath JAR checks, TestKit, public-project tests and synthetic measurements do not establish live Paper behavior, all supported JVM/OS combinations or remote CI success. JPMS descriptor/`requires` rewriting is not implemented. Do not claim unchanged performance, universal compatibility or commercial readiness from these checks. Repeat relevant validation after code changes; avoid rerunning unchanged expensive checks without a reason.

## Commit & Pull Request Guidelines

Recent commits use descriptive imperative subjects such as `Add ...`, `Remove ...`, and `Refactor: ...`. Keep commits focused. In pull requests, explain the behavior change, affected modules, and validation commands/results; link related issues when applicable. Update both `README.md` and `README.ja.md` for documented user-facing changes.

Include reusable verification scripts and durable developer/user documentation. Leave local handoffs such as `NEXT_SESSION_PROMPT.md`, dated one-off validation notes, build outputs, mappings, detailed reports and temporary logs out of commits unless explicitly requested. Preserve those local files instead of deleting them.
