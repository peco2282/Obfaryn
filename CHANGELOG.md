# Changelog

Format loosely follows [Keep a Changelog](https://keepachangelog.com/). No tags exist in this
repository yet; versions below are correlated with `gradle.properties`' `releaseVersion` at the
time of the "Bump to" commit, not with a Git tag.

## [Unreleased]

### Added

- `scripts/install.sh` (Linux/macOS) and `scripts/install.ps1` (Windows): install the CLI from a
  GitHub Release archive, symlink/PATH-wire the launcher, and (bash) install the `obfaryn
  completion bash` script automatically.
- `.github/workflows/release.yml`: on a `v*` tag, builds `:obfaryn-cli:distTar`/`distZip`, runs
  `scripts/verify-cli-distribution.py` against `installDist`, and attaches both archives to a
  GitHub Release.
- `obfaryn-cli`'s `distTar` is now gzip-compressed (`obfaryn-<version>.tar.gz`), matching what the
  install scripts expect; `distZip` was already available from the `application` plugin.
- `install.sh`/`install.ps1` show colored status output; `install.sh` also shows curl's real-time
  `--progress-bar` download progress on a TTY, falling back to silent when piped.
- `docs/assets/obfaryn-logo.png`: the project logo, now at the top of README/README.ja.md.
- `scripts/banner.sh`: an animated ASCII logo banner (static fallback when not an interactive
  color terminal); `install.sh` sources it from the local checkout or fetches it from the repo
  when piped (`curl | bash`), and shows it before installing.
- `.github/workflows/release.yml` also accepts `workflow_dispatch` with a `tag` input, to
  (re)build and attach assets to an existing tag's release without moving the tag. A tag checked
  out from before the `distTar` gzip-compression change (above) would otherwise only produce a
  plain `.tar`; the workflow now gzips it itself so `obfaryn-*.tar.gz` is always attached.
- Root `build.gradle.kts` aggregates every published module's Dokka output into one HTML API
  reference (`./gradlew dokkaGeneratePublicationHtml`); `.github/workflows/docs.yml` publishes it
  to GitHub Pages on every push to `main` and on tags, linked from README/README.ja.md.
- `obfaryn-platform-kit`: Kotlin extension functions for writing a `Platform` without working
  directly against raw ASM types or hand-written `(owner, name, descriptor)` triples —
  `isSubtypeOf`/`isAnnotatedWith` (string- and `Class<*>`/reified-based), `methodCallClosure` for
  propagating a hot-path classification to whatever a seed method calls, `Class<*>`/`Method`/
  `Constructor`/`Field` → descriptor/`MemberKey` conversions, and matching `PlatformConfiguration`
  overloads (`keepMethod`/`keepField`/`keepConstructor`/`excludeMethodFromVirtualization`) that take
  a real reflected member. `platforms/paper` is rewritten against it with no behavior change.
- `platforms/springboot` (`obfaryn-platform-springboot`): obfuscation support for Spring Boot
  applications, detected from an `@SpringBootApplication` class or a `META-INF/MANIFEST.MF`
  `Start-Class` attribute (no Spring Boot/Framework jars required on the classpath; a Spring Boot
  executable jar's nested `BOOT-INF/classes`/`BOOT-INF/lib` layout is not unpacked — point it at an
  already-exploded set of classes or a plain, non-fat jar). Protects `@Scheduled`/`@EventListener`/
  `@Async` methods and the application's `main` method, plus whatever they call, from
  virtualization.
- `ConcurrencyAndLanguageDifferentialTest` (`obfaryn-standard`): a differential regression that
  applies the combined strong configuration to a Java fixture and compares original vs. transformed
  behavior for constructs the existing stateful fixtures did not exercise — deep/mutual recursion,
  multidimensional array identity (shallow `clone`, `copyOf`, shared/detached rows, `deepHashCode`),
  `enum` constant-specific bodies with `switch`/`values`/`valueOf`, interface default-method diamond
  dispatch, `synchronized` sections, and multithreaded contention over `ConcurrentHashMap.merge`/
  `AtomicLong`. The concurrent scenario asserts equality plus the interleaving-independent invariant
  that no increment is lost, never order/log equality. Documented in
  `docs/semantic-validation.md` (and `.ja.md`).
- `.github/workflows/verification.yml`: the synthetic `tick-benchmark` job now also runs on the
  weekly `schedule` (not only manual dispatch), always uploads the machine-readable
  `build/performance/` results (`if: always()`), and accepts `workflow_dispatch` inputs
  (`strict_performance_gate`, `max_p99_ratio`, `max_extra_bytes_per_tick`) that opt into
  `scripts/performance/run.py`'s regression gate. The gate stays off by default so shared-runner
  timing noise does not fail the job; enable it only on a dedicated/quiet runner.
- `scripts/performance/virtualization.py` + `scripts/performance/VmBenchmark.java`: a reusable
  microbenchmark isolating the interpreter cost of method virtualization. Both jars run through the
  engine with every ordinary pass disabled, so the only difference is whether the measured method is
  virtualized. Measures a dispatch-bound (`hot`) and an invocation-bound (`tiny`) target against the
  original, reporting cold/warm percentiles, per-call allocation and a behavioral-equivalence
  checksum, into machine-readable `build/performance-vm/results.json`. A new `vm-benchmark` job in
  `verification.yml` runs it on manual dispatch and the weekly schedule and uploads the results
  (published without a CI gate; fixed-host gating is available via `--max-p99-ratio`).
- `docs/performance.md` (+ `.ja.md`): documents both performance benchmarks, the measured
  virtualization overhead characterization (≈14× dispatch-bound, ≈2.3× invocation-bound on JDK 21,
  host variance ≈ ±9%), and why two prototyped protection-neutral interpreter micro-optimizations
  were measured and then not adopted (no change distinguishable from host noise). The VM template is
  unchanged.

### Changed

- Transformation report JSON is now schema version 2: each method row adds `growthPercent`
  (`outputInstructions * 100 / inputInstructions`, or `-1` for a generated helper/initializer with
  no input body) and `appliedConstraints` (the structural budget actually in effect for that method:
  `maxInputInstructions`, `maxInputBranches`, `maxAddedInstructions`, `maxGrowthPercent`,
  `growthAllowanceInstructions`, `maxOutputCodeBytes`, or `null` for a non-body-budgeted pass). The
  limits were previously only surfaced indirectly through a rejection reason. `MethodTransformationReport`
  gains matching `growthPercent` and `appliedBudget` properties (additive, defaulted). Version 2 is
  backward compatible — every version 1 field keeps its name and meaning. Documented in
  `docs/protection.md` (and `.ja.md`); covered by `TransformationReportDetailTest`, which also guards
  that the report (it carries original names) is a standalone file and never an entry in the output JAR.

## [1.0.0]

First release with a defined API stability contract — see
[docs/api-stability.md](docs/api-stability.md) ([日本語](docs/api-stability.ja.md)) for exactly
what is and isn't covered by semver going forward.

### Fixed

- `module-info.class`'s `exports`/`opens` directives and its concealed-package list are now
  rewritten to track relocation under `--flatten-packages`. Previously they stayed at their
  original package name even when every class in that package moved, leaving a descriptor that
  claimed the wrong location. `uses`/`provides`/the module's main class already followed renamed
  classes correctly before this fix, independent of flattening. `requires` directives and the
  module's own declared name are still never rewritten, by design.

### Added

- `scripts/verify-jpms-module.py`, run in CI: builds a real `module-info`-bearing fixture,
  transforms it with `--flatten-packages`, and confirms the descriptor is correct and the output
  runs both on the classpath and on the module path (`java -p out.jar -m <module>`).
- CLI `--stacktrace`: prints the full cause chain and stack trace on failure instead of just the
  one-line message. Off by default; consumed before parsing, like `--preset`/`--preset-dir`.
- `docs/api-stability.md`/`.ja.md`: the stability policy referenced above.
- README/README.ja.md: documented a measured `@Virtualize` call-overhead order of magnitude
  (~60x median latency in a local synthetic benchmark; see the "Method virtualization" section).

### Changed

- README/README.ja.md: added a Quickstart with five usage patterns (Gradle defaults, Gradle with
  a seed/strong protection, CLI defaults, CLI with common flags, targeting a platform); the Gradle
  plugin section now leads with the real published release repository instead of a local
  `includeBuild`.
- Removed the maintainer-only publish workflow (`secrets.properties`, signing keys, `./gradlew
  publish` usage) from the public README; that is documented nowhere now, since only the
  maintainer publishes.
- Fixed several long-dangling links to `docs/implementation-status.md`, which was never a tracked
  file (README.md/.ja.md, obfaryn-api's READMEs, obfaryn-runtime's README, AGENTS.md).

### Validation for this release

- Live Paper 1.21.8 run of a `--flatten-packages`/`strong`-protected plugin (BallGame): loads,
  enables, and disables cleanly.
- Local Maven publish, consumed from a separate throwaway Gradle project (plugin +
  `obfaryn-annotations` `compileOnly` + a `keep` rule) end to end.
- Remote CI (`Verification` workflow) green on `main`.
- A combined-feature correctness suite (sealed interfaces, records, pattern-matching switch,
  enums with abstract methods, default interface methods, lambdas/method references/closures,
  streams, varargs, try-with-resources, exception cause chains, reflection on methods and fields,
  deep non-tail recursion, and an 8-thread/160k-operation concurrent read/write/`synchronized`
  stress test) under every strong-protection feature simultaneously: byte-for-byte identical
  output to the unobfuscated baseline across 5 runs, zero rejected transformations.
- A deliberately tangled control-flow stress test (goto-style state machine, labeled
  break/continue across nested loops, switch fallthrough, try/finally interacting with
  continue/return, mutual recursion) under the same full protection: correct output. One
  unreproducible `Failed transforming with rename` failure was observed once; 23 immediate
  retries of the identical invocation all succeeded. Not a confirmed defect, but not fully
  explained either — `--stacktrace` (added this release) would help diagnose a recurrence.
- Execution under real JDK 8 and JDK 17 runtimes (not just targeted bytecode versions), including
  the embedded prelink-runtime path under JDK 8.
- Synthetic scale check: a 20,000-class application obfuscates correctly with `strong` protection
  in ~34s; enabling `--call-indirection` broadly across that many call sites adds tens of seconds
  of first-use `invokedynamic` linkage cost at startup (not reduced by `--prelink-calls`, which
  only moves when the cost is paid). Not yet mitigated or warned about automatically — worth
  being deliberate about `--call-indirection`'s scope on large applications.

## [0.2.0]

### Added

- `TransformationPackageProvider` is now the one ServiceLoader-discoverable extension point for
  everything that only needs `TransformerRegistry` (add/replace/disable passes) as well as
  anything that also needs `AssemblyContext`: ignore the `context` parameter when a recipe only
  needs the registry (see `examples/custom-extension`'s `ExamplePackageProvider`).
- `obfaryn list-extensions` (CLI) and the Gradle `listObfarynExtensions` task: list every
  discovered platform/transformation-package provider ID visible to the host, without reading an
  input JAR or running a transformation.
- Named presets: CLI `--preset NAME` expands to Picocli's own `@<dir>/NAME.args` syntax, resolved
  against `--preset-dir` (repeatable), then project-local `.obfaryn/presets/`, then user-global
  `~/.obfaryn/presets/`. `obfaryn list-presets` discovers what's available without applying one.
- `withExtensionClassLoader`/`ExtensionListing` (`obfaryn-core`): shared implementation behind the
  CLI and Gradle plugin's extension-classloader handling and provider listing, replacing four
  near-identical hand-rolled copies.

## [0.1.14] and earlier

See prior commit history; no changelog was kept before this file was added.
