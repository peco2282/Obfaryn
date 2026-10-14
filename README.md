# Obfaryn

[Japanese](README.ja.md)

Obfaryn is an ASM-based obfuscator for Kotlin/JVM and Java JARs. It strips debug information, encrypts string literals, transforms numeric constants, conservatively renames symbols, and optionally applies control-flow changes, method virtualization, and offline JAR integrity verification.

## Requirements

The build uses JDK 21 and Kotlin Gradle Plugin 2.4.21. The public `obfaryn-api` and `obfaryn-annotations` modules use Kotlin language/API version 2.1 and Java 8 bytecode. The core and CLI require Java 11 or later at runtime; the Gradle plugin requires Java 17 or later. The Java version needed by an obfuscated JAR also depends on the input and enabled transformations.

| Module | Purpose |
| --- | --- |
| `obfaryn-api` | Platform and transformer interfaces |
| `obfaryn-annotations` | Source-level transformation policies |
| `obfaryn-core` | Obfuscation engine |
| `obfaryn-cli` | Command-line application |
| `obfaryn-gradle-plugin` | Gradle integration |

## CLI

On Windows, build and run the application distribution:

```powershell
.\gradlew.bat :obfaryn-cli:installDist
.\obfaryn-cli\build\install\obfaryn\bin\obfaryn.bat input.jar -o output.jar --platform paper --seed 123 --keep com.example.PublicApi
```

On other platforms, use `./gradlew` and the generated `bin/obfaryn` script. `--platform` accepts `auto` (default), `generic`, and `paper`.

| Option | Effect |
| --- | --- |
| `--mapping path` | Set the mapping path. By default, renaming creates `*.mapping.txt` beside the output JAR. |
| `--report path` | Write transformation counts and warnings as JSON. |
| `--dry-run` | Show planned counts and mapping without writing a JAR or mapping. |
| `--verify-output` | Validate written classes with ASM `CheckClassAdapter`. |
| `--classpath dependency.jar` | Add an external dependency; repeat as needed. |
| `--recompute-frames` | Recalculate StackMap frames. |
| `--application-package package` | Add an application package root; repeat as needed. |
| `--strip-generic-signatures`, `--keep-method-parameters` | Control signature and parameter metadata. |
| `--no-string-encryption`, `--no-number-obfuscation`, `--no-rename` | Disable individual passes. |

`--keep` accepts an exact class, `com.example.*` for one package, `com.example.api.**` recursively, `com.example.Class#method()V` for a method, and `com.example.Class#field` for a field. Method signatures use JVM descriptors. `@KeepName` preserves only a declaration's name.

To translate a stack trace using a saved mapping:

```shell
obfaryn deobfuscate-stacktrace --mapping output.mapping.txt stacktrace.txt
```

Class names and unambiguous method names can be restored. Removed line numbers cannot.

### Compatibility rules

Kotlin metadata mode defaults to `AUTO`. Paper or manifest entrypoints establish application package roots: metadata is stripped from application classes under those roots, while bundled classes in other packages keep their names. Add roots with `--application-package`. `--preserve-kotlin-metadata` keeps metadata and suppresses renaming of affected Kotlin classes. `--strip-kotlin-metadata` strips it throughout the JAR. Stripping may break Kotlin reflection or `kotlinx.serialization`; test in the target runtime.

Virtual methods are renamed together with their override family inside the JAR. Families overriding an external API (such as `Runnable.run`) or depending on an unresolved external superclass are kept. Supply dependencies with `--classpath` to improve hierarchy and access analysis. If frame recomputation requires an unresolved external type, Obfaryn fails instead of guessing frames; otherwise existing frames are retained.

Enum class names follow normal keep rules, while enum constant names are retained for `name()` and `valueOf()`. Annotation type names may change, but annotation element names are retained. Keep any declaration addressed by external code or configuration.

Eligible `static final` and Kotlin `const val` string and numeric fields inside application packages are moved from `ConstantValue` attributes into class initialization so literal passes can transform them. Explicitly kept fields are excluded. These fields cease to be Java compile-time constants, so keep constants that form an external API.

## Gradle plugin

The plugin ID is `com.peco2282.obfaryn`. To use a local checkout, add it to the consuming project's `settings.gradle.kts`:

```kotlin
pluginManagement { includeBuild("../Obfaryn") }
```

Configure the consuming project's `build.gradle.kts`:

```kotlin
plugins {
  java
  id("com.peco2282.obfaryn")
}

dependencies {
  compileOnly("com.peco2282.obfaryn:obfaryn-annotations:1.0-SNAPSHOT")
}

obfaryn {
  platform = "paper"
  seed = 123L
  verifyOutput = true
  recomputeFrames = true
  kotlinMetadataMode = "auto" // auto, preserve, strip
  applicationPackages = listOf("com.example.app")
  keep = listOf("com.example.api.*")
  rename {
    classes = true
    methods = true
    fields = true
  }
}
```

`obfuscateJar` uses `shadowJar` when available, otherwise `jar`, and writes `build/libs/*-obfuscated.jar` with a mapping. The plugin automatically supplies `runtimeClasspath` and `compileClasspath` for method renaming, frame recomputation, and annotation-selected virtualization.

## Core and platform API

`Obfuscator(ObfuscationConfig(...)).obfuscate(input, output)` is the core entry point:

```kotlin
val config = ObfuscationConfig(
  platform = "paper",
  platformConfiguration = {
    keepClass("com.example.PublicApi")
    keepMethod("com.example.Plugin", "publicEntry", "()V")
    keepResource("config.yml")
  },
)
Obfuscator(config).obfuscate(input, output)
```

`Platform<C : PlatformConfiguration>` provides detection, fresh configuration, resource and performance policies, and entrypoints. Register custom platforms in `META-INF/services/com.peco2282.obfaryn.api.Platform`. IDs must be unique and nonempty; `auto` is reserved. Automatic selection checks Paper, external providers, then generic. Custom configurations must implement `applyTo` to copy keep rules into the active run. `excludeMethodFromVirtualization` retains normal JVM bytecode for a method while allowing other obfuscation passes. External platform implementations should be recompiled after API contract changes.

## Exclusions and control flow

The CLI accepts repeated `--exclude-class regex`, `--exclude-resource regex`, `--exclude-renaming regex`, and `--exclude-virtualization regex`. Regexes are **full matches** against original JVM internal class names (`com/example/Type`), resource paths, or `owner.method(descriptor)`. Invalid patterns fail before transformation.

`excludeClasses` suppresses body changes and renaming, but references to renamed declarations can still be updated. `excludeRenaming` suppresses name changes only. `excludeResources` suppresses resource transformation, although obsolete JAR signatures are still removed. Excluded entries are therefore not guaranteed to be byte-identical.

`--target-package hidden --flatten-packages` moves eligible renamed classes into a common package. Both settings are required together. Obfaryn conservatively preserves packages with protected structure, package-private access, unresolved same-package references, or relative resource lookups and reports a warning. It does not rewrite arbitrary class names embedded in configuration files.

`--control-flow` applies branch inversion and keyed switch dispatch. Constructors, class initializers, and large methods are excluded. Control flow and virtualization automatically trigger frame recomputation; external dependencies may be needed on the classpath.

## Method virtualization

Prefer `@Virtualize` on selected classes or methods. Pattern-based selection is experimental and requires `@file:OptIn(VirtualizeOptIn::class)` in the Gradle Kotlin DSL:

```kotlin
@file:OptIn(com.peco2282.obfaryn.gradle.VirtualizeOptIn::class)

obfaryn {
  virtualize {
    methods = listOf("com/example/logic/Calc\\.compute\\(I\\)I")
    excludeMethods = listOf("com/example/logic/Calc\\.tick\\(.*")
    runtimeGroupSize = 8
    mode = "strict" // strict or eligible
    analyzeOnly = false
    report = "build/reports/obfaryn/virtualization.json"
  }
}
```

The CLI equivalents are `--virtualize-method regex`, `--exclude-virtualization regex`, `--virtualization-runtime-group-size N`, `--virtualization-mode strict|eligible`, `--virtualization-analyze-only`, and `--virtualization-report path`. Explicit exclusions take priority. `strict` fails before transformation if any selected method is unsupported. `eligible` leaves unsupported methods as JVM bytecode and warns. Analysis-only mode creates no JAR, mapping, runtime, or bridges.

`@HotPath` excludes latency-sensitive methods from virtualization. `@KeepName` retains the name only; `@RenameOnly` allows renaming but suppresses body passes; `@NoObfuscation` suppresses both. These binary-retained annotations add no runtime dependency. Method annotations can override class-level `@HotPath`, `@RenameOnly`, or `@Virtualize`; class-level `@NoObfuscation` is final. Contradictory annotations on the same declaration fail validation.

```kotlin
@HotPath
fun monitorEntities() { /* frequently called: keep JVM bytecode */ }

@Virtualize
fun determineMatchResult(): Int = 1 // select an infrequent, sensitive method

@RenameOnly
fun updateProjectilePositions() { /* rename without changing the body */ }

@NoObfuscation
fun externallyReferencedEntryPoint() { /* keep the name and body */ }
```

Paper excludes lifecycle callbacks, `@EventHandler`, `Runnable`/`BukkitRunnable.run()V`, no-argument `tick()V` and `onTick()V`, and methods they call within the JAR. The analysis report (schema version 2) lists policy exclusions and reasons. Use `@HotPath` or an explicit exclusion for other frequently called methods.

The compiler translates supported bytecode into a typed, block-based IR; its verifier checks locals, branches, stack bounds, value kinds, and merge points. The output JAR contains interpreters and integer-ID bridges for selected field, invocation, cast, array, and constructor operations. Obfaryn itself is not needed at runtime. Supported values include int-like, reference, float, long, double, and void returns. Arrays, branches, loops, throws, and selected calls are supported. Method-body constructors, class initializers, monitors, try/catch, arbitrary uninitialized objects, switches, and legacy subroutines are not virtualized. Only straight-line `NEW → DUP → arguments → invokespecial <init>` constructor calls meeting safety checks can become bridges.

The IR retains JVM local indexes; long and double advance the index by two. Float and double values use raw-bit storage so negative zero and NaN reach bridges intact. Decoded programs are cached lazily, and small execution frames are reused per thread. Layouts may reorder blocks, make fallthrough explicit, invert conditions, and fuse selected constant arithmetic operations. These fusions currently cover addition and multiplication with constants, rather than arbitrary generated handlers.

`runtimeGroupSize` must be positive and defaults to 8. Groups stay within the original package to preserve access. A value of 1 creates a runtime per method but increases class count and memory use; 16–32 favors lower overhead. Seeded layouts produce reproducible output for the same input, settings, seed, and tool version. Runtime code and keys remain analyzable; virtualization does not guarantee cryptographic secrecy.

Each runtime group varies instruction IDs, word width, slot layout, header and mask positions, branch-table shape, and encoding keys. Unused words are randomized and contribute to subsequent decoding state. Bridges live as synthetic static methods in the original caller class, use ordinary JVM access instructions, and follow later renaming. The runtime is still specialized from a Java template; method-to-program correspondence remains recoverable by analysis.

To inspect eligibility without creating an output JAR:

```shell
obfaryn input.jar --virtualize-method 'com/example/.*' --virtualization-analyze-only --virtualization-report virtualization.json
```

Reports contain original symbols; treat them as build information rather than public artifacts.

The JSON report includes schema version 2, analyzed/selected/eligible/rejected counts, policy exclusions, per-method reasons with instruction positions, opcode and reference summaries, estimated VM instruction counts, and bridge counts. Labels, frames, and line-number nodes are excluded from instruction counts. Selection matching no methods and selection yielding no eligible methods are reported separately.

## Offline JAR integrity verification

Supply **both** RSA keys to enable integrity protection:

```shell
openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:3072 -out private.pem
openssl pkey -in private.pem -pubout -out public.pem
obfaryn input.jar -o output.jar --integrity-private-key private.pem --integrity-public-key public.pem
```

The Gradle extension and core configuration use `integrityPrivateKey` and `integrityPublicKey`. The private key must be unencrypted PKCS#8 PEM/DER, the public key X.509 SubjectPublicKeyInfo PEM/DER, and RSA keys must be at least 2048 bits. Mismatched keys fail before output. Keep the private key outside source control and distributed JARs.

Obfaryn signs a SHA-256 list of final JAR entry paths and contents with SHA256withRSA and embeds only the public key. Verification runs at the first eligible class initialization and throws `SecurityException` for altered, missing, added, or duplicate files. The two integrity metadata entries are excluded from their own hash list. Directory entries, compression method, and timestamps are not checked. Signed input cannot be processed again, and at least one non-excluded class is required. Verification needs a file-backed JAR, with no server or Obfaryn runtime. It does not continuously monitor files or memory. An attacker who can replace the verifier and embedded public key can bypass it.

Environments that transform JARs after Obfaryn writes them must sign the final deployable artifact or the check will fail. Standard `jarsigner` is an alternative, but an application then needs to read every protected entry and pin a trusted certificate or public key; a signature by itself does not reject every unsigned addition.

## Safety and current limits

- Obfaryn does not rewrite `kotlin.Metadata`. Use `preserve` or runtime tests for reflection and metadata-dependent frameworks.
- Reflection and names stored in configuration may require explicit keep rules. Paper/manifest entrypoints and ServiceLoader names receive built-in protection.
- Multi-release JAR versioned classes are retained only when Obfaryn can rule out references to renamed application packages; otherwise processing fails.
- VM support covers selected bytecode patterns, not every JVM instruction. VM hardening, performance budgets, and resistance evaluation remain future work.
- Integrity protection detects changes according to its signed manifest; a self-contained JAR cannot guarantee that modification or reverse engineering is impossible.
