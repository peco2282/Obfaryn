# Obfaryn

Feature-by-feature annotations, cumulative structural budgets, integer expressions, bounded prelinking and repeatable public-project/performance checks are documented in [Protection controls](docs/protection.md).

For completed scope, recorded validation and remaining release/runtime checks, see [Implementation status](docs/implementation-status.md).

Generated helper classes from budgeted body passes participate in code-size budgets and per-method reporting. Output-path checks also reject hard-link and symbolic-link aliases of inputs and other artifacts.

Gradle supports `controlFlow { mode = "light" }`, `calls { enabled = true; prelink = true }`,
`arithmetic { enabled = true; maxOperationsPerMethod = 4 }`, `reports { methods = "build/methods.json" }`
and `rules { method("com/example/.*") { calls = false } }`. CLI counterparts include
`--control-flow-mode light` and `--disable-feature 'calls=com/example/.*'`.
See [configuration examples and precedence](docs/protection.md#gradle-and-cli-configuration).

## Distribution and releases

All modules share one version: `0.0.0-SNAPSHOT` by default, overridden with
`-PreleaseVersion=0.1.0` (example only; not an announced release).
With JDK 21, prepare a local Maven repository and the standalone CLI distribution:

```shell
./gradlew publish :obfaryn-cli:distZip :obfaryn-cli:distTar -PpublishRepositoryUrl="file:$PWD/build/repository"
```

This command explicitly selects `build/repository`, even with `secrets.properties` present.
Libraries include sources, Dokka Javadoc, MPL-2.0 license text, and POM metadata.
CLI archives are in `obfaryn-cli/build/distributions`; extract the entire archive
and run `bin/obfaryn` (`bin/obfaryn.bat` on Windows), not the thin CLI JAR alone.
The CLI does not bundle the optional Paper provider.

Consume the generated repository from another build (replace the absolute path):

```kotlin
repositories {
  maven { url = uri("/absolute/path/to/Obfaryn/build/repository") }
  mavenCentral()
}
dependencies {
  implementation("com.peco2282.obfaryn:obfaryn-core:0.0.0-SNAPSHOT")
  // For extension-only projects, use obfaryn-api instead.
  // Optional: implementation("com.peco2282.obfaryn:obfaryn-platform-paper:0.0.0-SNAPSHOT")
}
```

For the Gradle plugin, add the same repository plus `mavenCentral()` and
`gradlePluginPortal()` to `pluginManagement.repositories` in the consumer's
`settings.gradle.kts`, then use
`id("com.peco2282.obfaryn") version "0.0.0-SNAPSHOT"` in its plugins block.
The plugin marker is published together with the implementation.

`obfaryn { ... }` is a configuration-only DSL: it exposes no core instances or
execution operations. It accepts custom Transformers, string/number strategies,
and `pipeline { ... }`; the plugin calls core directly only when `obfuscateJar`
executes. No dedicated runtime configuration or classloader is needed. Core is a
normal plugin dependency, so build scripts may still explicitly import and call
its classes. The DSL is not a security boundary restricting arbitrary code.

Before an external release, run `./gradlew test`, inspect the local repository,
and test a separate consumer against it. Run `./gradlew :obfaryn-cli:installDist` and
`python3 scripts/verify-cli-distribution.py` to check the installed CLI, embedded
call-linkage runtime, and execution of a transformed Java 8 fixture. This check
does not validate Maven publications or a separate Gradle consumer.
To publish, create `secrets.properties` in the repository root (ignored by Git):

```properties
releaseUrl=https://maven.peco2282.com/maven-releases/
snapshotUrl=https://maven.peco2282.com/maven-snapshots/
user=user
password=password
```

All Maven publications, including the plugin marker, use `snapshotUrl` when the
version contains `snapshot` (case-insensitive), and `releaseUrl` otherwise:

```shell
./gradlew publish -PreleaseVersion=0.1.0
./gradlew publish -PreleaseVersion=0.2.0-SNAPSHOT
```

`-PpublishRepositoryUrl=...` overrides the destination, including for local
verification with a `file:` URL. Without `secrets.properties` or an override,
publication defaults to `build/repository`. With a secrets file, the selected URL
must be present and nonblank. `MAVEN_USERNAME` and `MAVEN_PASSWORD` are fallbacks
when `user` or `password` is absent from the file.
If signing is required, also set `SIGNING_KEY` (ASCII-armored private PGP key)
and `SIGNING_PASSWORD`; every Maven publication, including the plugin marker,
will be signed. Never commit these secrets. Without `SIGNING_KEY`, publishing
is unsigned. Use a snapshot repository for `-SNAPSHOT` versions and an immutable
release repository for final versions; never overwrite a released version.

This does **not** claim availability on Maven Central or the Gradle Plugin Portal.
Central requires namespace verification and its own staging/bundle submission
and validation process; the Portal likewise requires separate registration and
publishing. Configure the actual hosting service and credentials before sharing
public dependency coordinates.

[Japanese](README.ja.md)

Obfaryn is an ASM-based obfuscator for Kotlin/JVM and Java JARs. It strips debug information, encrypts string literals, transforms numeric constants, conservatively renames symbols, and optionally applies control-flow changes, method virtualization, and offline JAR integrity verification.

## Requirements

The build uses JDK 21 and Kotlin Gradle Plugin 2.4.21. The `obfaryn-api`, `obfaryn-annotations`, `obfaryn-core`, `obfaryn-transformer`, and CLI modules use Kotlin language/API version 2.1 and Java 8 bytecode. The optional `obfaryn-runtime` module is Java-only, targets Java 8 and has no external dependencies. The Gradle plugin requires Java 17 or later. The Java version needed by an obfuscated JAR also depends on the input and enabled transformations.

| Module | Purpose |
| --- | --- |
| `obfaryn-api` | Platform and transformer interfaces |
| `obfaryn-annotations` | Source-level transformation policies |
| `obfaryn-runtime` | Optional Java 8 prelink API and zero-dependency runtime templates |
| `obfaryn-core` | Obfuscation engine |
| `obfaryn-transformer` | Standard transformers (strings, numbers, control flow, metadata) |
| `obfaryn-cli` | Command-line application |
| `obfaryn-gradle-plugin` | Gradle integration |
| `platforms/paper` | External Paper platform provider (`obfaryn-platform-paper`) |

## License

Obfaryn's original source code, including its API, annotations, core, CLI, and Gradle plugin, is licensed under the [Mozilla Public License 2.0](LICENSE). If you modify and distribute an Obfaryn source file, make that file's source available under MPL 2.0. Separate files that merely use Obfaryn can have their own license. The Gradle Wrapper and third-party dependencies retain their respective licenses.

## CLI

On Windows, build and run the application distribution:

```powershell
.\gradlew.bat :obfaryn-cli:installDist
.\obfaryn-cli\build\install\obfaryn\bin\obfaryn.bat input.jar -o output.jar --platform paper --seed 123 --keep com.example.PublicApi
```

On other platforms, use `./gradlew` and the generated `bin/obfaryn` script. `--platform` accepts `auto` (default), `generic`, and IDs supplied by installed platform providers such as `paper`. The Paper example requires `obfaryn-platform-paper` on the runtime classpath.

With the installed distribution's `bin` directory on `PATH`:

```shell
obfaryn input.jar                         # Defaults to input-obfuscated.jar beside the input
obfaryn --seed=42 input.jar --keep-debug-info
obfaryn obfuscate input.jar -o output.jar  # Explicit command is optional
obfaryn @obfaryn.args
obfaryn --help
```

Options can appear before or after the input, and accept `--option=value`. Reuse long configurations with argument files. For example, `obfaryn.args`:

```text
input.jar
--output "build/protected jars/output.jar"
--seed 42
--keep 'com.example.api.**'
--keep-debug-info
```

Quote values containing spaces or `#`. `--keep-debug-info` preserves source/debug data. `--no-string-constant-fields` and `--no-number-constant-fields` disable field extraction independently without disabling method literal transformations.

### Shell completion

Generate and source the completion script in your shell to complete commands, options, choices, and file paths:

```bash
obfaryn completion bash > obfaryn-completion.bash
source ./obfaryn-completion.bash
```

For Zsh (the generated script initializes `compinit` / `bashcompinit`):

```zsh
obfaryn completion zsh > obfaryn-completion.zsh
source ./obfaryn-completion.zsh
```

Keep the generated file and source it from `.bashrc` / `.zshrc` for subsequent sessions. Regenerate it after CLI upgrades.

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

Kotlin metadata mode defaults to `AUTO`. Both `AUTO` and `PRESERVE` (`--preserve-kotlin-metadata`) retain application metadata and safely rewrite class names, types, JVM signatures, and Kotlin module maps during renaming or relocation; Eligible Kotlin functions, properties, and JVM member signatures are renamed together. Keep rules and external API overrides still apply. Names used by compiler conventions (including multifile facades, value-class ABI helpers, `@JvmStatic`, data-class components, and callable references) remain conservatively protected. Eligible default-argument methods and their `$default` helpers are renamed together, preserving Kotlin default calls and reflection. These protections are scoped to declaring classes and signatures; unrelated methods sharing a name are not kept. Enum constant names are retained. Entrypoints reported by the selected platform provider, or manifest entrypoints, establish application package roots; bundled classes outside known roots keep their names. Add roots with `--application-package`. Only explicit `STRIP` (`--strip-kotlin-metadata`) requests metadata removal throughout the JAR, subject to exclusions and annotation policies. Stripping may break Kotlin reflection or `kotlinx.serialization`; test in the target runtime.

Metadata compatibility follows `kotlin-metadata-jvm` 2.4.21 `readStrict`: readable metadata formats from 1.1 onward are supported, and formats older than 1.4 are upgraded to 1.4 when written. Unknown formats and opaque plugin payloads are explicitly rejected before output rather than rewritten unsafely. This is metadata-format compatibility, not a claim that every Kotlin compiler version has been tested.

Virtual methods are renamed together with their override family inside the JAR. Families overriding an external API (such as `Runnable.run`) or depending on an unresolved external superclass are kept. Supply dependencies with `--classpath` to improve hierarchy and access analysis. If frame recomputation requires an unresolved external type, Obfaryn fails instead of guessing frames; otherwise existing frames are retained.

Record class names can be renamed and relocated, including Kotlin nested records, without pinning their enclosing classes. Record component names, fields, accessors, and other record members remain protected together so reflection, canonical constructors, and generated object methods stay consistent. Explicit keeps, serialization identity, and native-method restrictions still apply.

Kotlin value classes can rename ordinary functions, properties, and backing fields alongside their class names. Boxing, unboxing, construction, and generated equality/hash/string ABI helpers retain their required names. JVM names used by Kotlin callers are pinned with coordinated `@JvmName` annotations and metadata, preventing consumers from recomputing incompatible mangled names after relocation. Existing `@JvmName` payloads and the underlying-property metadata are updated too. Kotlin consumer compatibility requires retained metadata.

Enum class names follow normal keep rules, while enum constant names are retained for `name()` and `valueOf()`. Annotation type names may change, but annotation element names are retained. Keep any declaration addressed by external code or configuration.

Eligible `static final` and Kotlin `const val` string and numeric fields inside application packages are moved from `ConstantValue` attributes into class initialization so literal passes can transform them. Explicitly kept fields are excluded. These fields cease to be Java compile-time constants, so keep constants that form an external API.

## Gradle plugin

The plugin ID is `com.peco2282.obfaryn`. To use a local checkout, add it to the consuming project's `settings.gradle.kts`:

```kotlin
pluginManagement { includeBuild("../Obfaryn") }
```

Configure the consuming project's `build.gradle.kts`:

Platform providers and their runtime dependencies are loaded from `obfarynPlatform` during obfuscation. They are not added to application classpaths or the output JAR. Match the Obfaryn plugin version and configure its publication repository (the example uses snapshots). Groovy DSL: `obfarynPlatform "com.peco2282.obfaryn:obfaryn-platform-paper:VERSION"`.

```kotlin
plugins {
  java
  id("com.peco2282.obfaryn")
}

repositories {
  maven { url = uri("https://maven.peco2282.com/maven-snapshots/") }
  mavenCentral()
}

dependencies {
  obfarynPlatform("com.peco2282.obfaryn:obfaryn-platform-paper:1.0-SNAPSHOT")
  compileOnly("com.peco2282.obfaryn:obfaryn-annotations:1.0-SNAPSHOT")
}

obfaryn {
  platform = "paper"
  seed = 123L
  verifyOutput = true
  recomputeFrames = true
  string {
    enabled = true
    includeConstantFields = true
    // strategy = RollingStringEncryptionCodec() // Paired encryption and decoder generation
  }
  number { enabled = true; includeConstantFields = true }
  metadata {
    stripDebugInfo = true
    kotlinMetadataMode = "auto" // auto, preserve, strip
    stripGenericSignatures = false
    stripMethodParameters = true
  }
  output {
    directory = "build/obfaryn"
    fileName = "app-obfuscated.jar"
    mapping = "build/mappings/app.txt"
  }
  applicationPackages = listOf("com.example.app")
  keep = listOf("com.example.api.*")
  rename {
    classes = true
    methods = true
    fields = true
  }
}
```

`obfuscateJar` uses `shadowJar` when available, otherwise `jar`. Without output settings it writes `build/libs/*-obfuscated.jar` with a mapping. The plugin automatically supplies `runtimeClasspath` and `compileClasspath` for method renaming, frame recomputation, and annotation-selected virtualization.

The nested `string` / `number` `enabled` flags and `metadata` properties share values with the existing flat properties (`stringEncryption`, `numberObfuscation`, `stripDebugInfo`, etc.); the last assignment wins. The same blocks work in Groovy DSL. `includeConstantFields = false` disables extraction only for that constant type. Both literal blocks accept an optional `random` generator; omitted generators follow the existing `seed`. To customize one, add `import java.util.Random` and set `random = Random(10)`. `string.strategy` accepts `StringEncryptionCodec`, pairing encryption with runtime decoder generation. Numeric customization uses `number.strategy` and `number.selector`.

`output.directory` / `fileName` customize the output folder and name. An explicit `output.file = "dist/protected.jar"` takes precedence over both. An omitted `mapping` is placed beside the output JAR. Paths are project-relative or absolute. The default directory follows Gradle's `buildDirectory`, and the existing `outputSuffix` still applies to default names. Input, output, and mapping paths must differ. Because custom strategy and random-generator state cannot be reliably fingerprinted, `obfuscateJar` runs on every invocation.

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

The platform SPI is framework-neutral: Paper, Velocity, private runtimes, and project-specific conventions all implement the same `Platform` interface. A `PlatformProvider` supplies metadata and creates a fresh platform for each run. Register it in `META-INF/services/com.peco2282.obfaryn.api.PlatformProvider`. `detect` returns a confidence score and reasons, while `configure` contributes keep, resource, reflection, and virtualization-performance policies. `entrypoints` reports externally invoked lifecycle classes. IDs must be unique and nonempty; `auto` is reserved, and provider API versions must match `PlatformApi.VERSION`.

Paper support lives outside core in `platforms/paper` and is published as `com.peco2282.obfaryn:obfaryn-platform-paper`. Core treats `plugin.yml` as an ordinary resource unless that provider is present.

Platform discovery locations are deliberately not part of the SPI. A future loader may receive any number of JAR files or directories from the CLI; implementations must not assume a directory named `platforms`. Platform-specific frontend settings use `PlatformOptions`, supplied by CLI as repeatable `--platform-option key=value` arguments or by Gradle through `platformOptions`.

```kotlin
class CustomPlatformProvider : PlatformProvider {
  override val metadata: PlatformMetadata = DefaultPlatformMetadata(
    id = "custom",
    displayName = "Custom Runtime",
    implementationVersion = "1.0.0",
  )

  override fun create(): Platform = CustomPlatform(metadata)
}

class CustomPlatform(
  override val metadata: PlatformMetadata,
) : Platform {
  override fun detect(context: PlatformContext): PlatformDetection =
    if ("custom-runtime.json" in context.resources)
      DefaultPlatformDetection(100, listOf("custom-runtime.json found"))
    else DefaultPlatformDetection(0)

  override fun entrypoints(context: PlatformContext, options: PlatformOptions): Set<String> =
    setOf("example/Bootstrap")

  override fun configure(
    context: PlatformContext,
    options: PlatformOptions,
    target: PlatformConfiguration,
  ) {
    target.keepResource("custom-runtime.json")
    target.keepClassNameOnly("example/Bootstrap")
    target.excludeMethodFromVirtualization("example/Bootstrap", "tick", "()V", "custom hot loop")
  }
}
```

Platforms may return constrained extensions from `Platform.extensions()`. `AnnotationPolicyProvider`
maps third-party binary annotations to `NO_OBFUSCATION`, `RENAME_ONLY`, `KEEP_NAME`, `VIRTUALIZE`,
or `HOT_PATH` without adding them to `obfaryn-annotations`. `ResourceContributor` adds non-class JAR
entries, while `ClassContributor` adds complete compiled class files. Added classes enter the normal
Obfaryn pipeline and therefore participate in renaming, package flattening, body transforms, frame
calculation, and verification.

`ArchiveContributor` supports `MERGE` and `EMBED_AS_RESOURCE`. Merge rejects duplicate entries,
signed archives, `module-info.class`, and multi-release entries; manifests are not copied. Embedded
archives require an explicit resource path and are treated as data. Source compilation and arbitrary
ASM transformers are intentionally not part of the platform extension API.

## Transformer extension API

For complete beginner-friendly implementation and launcher examples, see the [API development guide](obfaryn-api/README.md). It explains dependencies, contexts, platform providers, literal algorithms, and validation.

External extensions implement `Transformer` and `PipelineConfigurer` from `obfaryn-api`; the launcher passes them to core. String encryption, number obfuscation, control flow, constant-field conversion, and metadata stripping live in `obfaryn-transformer`. Its project dependencies are API and the small runtime-template module (it also uses ASM); it does not depend on core or a platform. Existing package names are retained, and core exposes the module transitively.

Renaming, virtualization, and standard pipeline assembly remain in core pending API contracts for their configuration, name preservation, and analysis results. This is incremental separation, not yet a fully independent execution engine.

```kotlin
import com.peco2282.obfaryn.api.transformer.PipelineConfigurer

val extension = PipelineConfigurer {
  // Create stateful passes here to avoid sharing state across runs.
  val customTransformer = CustomStringTransformer() // id = "string-encryption"
  if (this[customTransformer.id] != null) {
    replace(customTransformer.id, customTransformer)
  } else {
    this += customTransformer
  }
}

Obfuscator(config = config, pipelineConfigurers = listOf(extension))
  .obfuscate(input, output)
```

`registry["id"]` returns a registered pass or `null`. `add` / `+=` reject duplicate IDs; `disable` rejects unknown IDs; `replace` requires an existing ID and a matching replacement ID. Disabled standard passes are not registered. Configurers run in list order after the legacy `configurePipeline` callback, before missing dependencies and cycles are validated. Dependencies take precedence over numeric order; ready passes are selected by `order`, then ID. The last replacement wins.

Custom body transforms should consult `context.transformationPolicy.canTransformBody(owner, method)`, as the standard body transforms and method metadata stripping do. Class/field metadata stripping consults `canTransformMetadata(owner)`; constant conversion consults `canTransformConstant(owner, field)`. The two additional methods default to allowing changes and preserve SAM construction. Custom contexts allow all transformations by default. Policies are advisory, not enforced guards around ASM mutation. Existing annotation semantics, including `@HotPath`, are unchanged.

### Custom number expressions

Implement the `obfaryn-api` interface `NumberObfuscationStrategy`: `generateInt(value, random)` and `generateLong(value, random)` only generate expressions. The default `generateFloat` / `generateDouble` methods reuse these integer expressions with raw-bit conversion, preserving signed zero and NaN payloads. Override them for dedicated floating-point expressions. Return `null` to skip a constant, or fresh non-empty `InsnList` instructions pushing the original JVM value without disturbing the stack or locals. Branches require valid frames; runtime dependencies are not bundled.

Target selection is separate: `NumberObfuscationSelector.shouldTransform(site, random)` receives context, owner, method, instruction and value for annotation checks or sampling. Treat these ASM nodes as read-only. The transformer checks body policy first, then selection, dispatches by type, and replaces instructions. `DefaultNumberObfuscationSelector` skips integer/long constants from -1 to 1; `DefaultNumberObfuscationStrategy` only generates the three standard expressions. Use `selector = NumberObfuscationSelector { _, _ -> true }` to include small constants.

```kotlin
val passes = transformers {
  number {
    strategy = MyNumberStrategy()
    selector = NumberObfuscationSelector { site, random ->
      site.method.name != "hotLoop" && random.nextInt(100) < 70
    }
  }
  string { codec = MyStringCodec() }
}
```

Import `NumberObfuscationSelector` from `com.peco2282.obfaryn.api.transformer`. Core configuration exposes `numberStrategy` / `numberSelector`; Gradle exposes `number { strategy = ...; selector = ... }`. Constant fields are extracted before expression generation; field exclusions belong in the constant transformation policy.

### Custom string encryption algorithms

Implement `StringEncryptionCodec` from `obfaryn-api`, pairing `encrypt(value, key): StringEncryptionResult` with `generateDecryptor(owner, method)`. Codec implementations need only depend on the API module. Select one with `string { codec = MyCodec() }` in the standard pass builder, or register `StringEncryptionTransformer(java.util.Random(10), MyCodec())` through a `PipelineConfigurer`.

The decryptor is generated once per class that needs it. Decoded literals are lazily cached in fixed per-class slots and interned to preserve JVM literal identity. Repeated reads use an atomic cache lookup; concurrent first reads may decode more than once. Oversized encoded payloads are split into legal constant-pool chunks and assembled only on a cache miss. Cache storage grows with the number of transformed literal sites, and plaintext remains reachable after first use. Custom decryptors must be deterministic and safe for concurrent invocation. Its arguments are the encrypted result's `payload: String` and effective `key: Int`, and it returns the original string. Preserve the supplied `MethodNode` name, descriptor and access flags; emit instructions, max locals/stack, and stack map frames for branches unless the host enables frame recomputation. Any external runtime dependencies must be available in the output application's environment; codec implementation classes are not automatically bundled.

`codec` defaults to `RollingStringEncryptionCodec()`, which masks UTF-16 code units using keyed state updates and ciphertext feedback. It preserves isolated surrogates and NUL characters. This is obfuscation, not cryptographic secrecy: decoding code and keys remain in the output. `XorStringEncryptionCodec()` remains available for compatibility. Standard and custom algorithms use the same interface and execution path; the encrypt-only `StringEncryptionStrategy` has been removed. Core's `stringEncryptionStrategy` and Gradle's `string.strategy` also accept `StringEncryptionCodec`. `encrypt` runs during obfuscation; `generateDecryptor` emits the runtime decoding body rather than decoding a value inside the obfuscator. Annotation-based body policies still take precedence.

### Standard pass builders

`obfaryn-transformer` provides `TransformersBuilder` and public `StringBuilder` / `NumberBuilder` / `MetadataBuilder` interfaces with internal implementations:

```kotlin
import com.peco2282.obfaryn.transformer.builder.transformers

val passes = transformers {
  string {
    codec = com.peco2282.obfaryn.transformer.RollingStringEncryptionCodec()
    includeConstantFields = true
    random = java.util.Random(10)
  }
  number { includeConstantFields = false }
  metadata { stripDebugInfo = true }
}
```

`string()` and `number()` snapshot their options when registered. `includeConstantFields` defaults to `true`; `build()` adds at most one shared constant-field preprocessor for the requested value types. No pass is added for an unrequested type. Duplicate string/number registrations and conflicting manual constant preprocessors are rejected. The old `constants {}` / `ConstantsBuilder` / `ObfTransformers.constants*` entry points are replaced by these options; move strategy/random arguments into `string {}` or `number {}`. Create a builder and passes per execution; repeated `build()` calls snapshot the list but reuse registered pass instances. Random defaults are not tied to the host seed; supply seeded `java.util.Random` instances for reproducibility. Core still validates dependencies and orders passes. To replace standard passes, use `PipelineConfigurer` and `replace` (or `add` when absent), including the preprocessor as needed.

`context.transformationInputs` supplies application scope, constant preservation and per-class Kotlin metadata stripping decisions. Core populates it from the current run, including for custom passes and filtered contexts. Unknown scope defaults to no application classes, not all classes. The legacy constructor arguments for roots, keep predicates and metadata class sets remain available; new builders need only user options. Explicit roots override context scope, keep decisions are combined, and metadata strip requests are additive. Existing annotation policy checks remain unchanged. Standard assembly and the core-to-transformer dependency have not yet been removed.

Virtualization selection and name preservation are not yet part of this shared policy. Replacing `rename` is unsupported until mapping, counts, and reference updates in excluded classes have a common reporting contract. Paper-specific callback detection remains in `platforms/paper`.

## Exclusions and control flow

The CLI accepts repeated `--exclude-class regex`, `--exclude-resource regex`, `--exclude-renaming regex`, and `--exclude-virtualization regex`. Regexes are **full matches** against original JVM internal class names (`com/example/Type`), resource paths, or `owner.method(descriptor)`. Invalid patterns fail before transformation.

`excludeClasses` suppresses body changes and renaming, but references to renamed declarations can still be updated. `excludeRenaming` suppresses name changes only. `excludeResources` suppresses resource transformation, although obsolete JAR signatures are still removed. Excluded entries are therefore not guaranteed to be byte-identical.

`--target-package hidden --flatten-packages` moves eligible renamed classes into a common package, including Kotlin classes with safely rewritten metadata. Kotlin nested and inner classes also receive short names while retaining their enclosing-class relationship; companion object names are preserved for Kotlin reflection. Both settings are required together. Keep rules still apply. Obfaryn conservatively preserves packages with protected structure, package-private access, unresolved same-package references, or relative resource lookups and reports a warning. It does not rewrite arbitrary class names embedded in configuration files.

`--control-flow` rewrites eligible methods into keyed switch dispatch. Transitions derive the next state from the current state using per-edge XOR or addition, and numeric payloads inside transformed blocks are decoded using that block state. This couples numeric recovery to control-flow analysis, but does not prevent symbolic recovery. Methods that cannot be flattened retain the configured numeric strategy. The additional integer operations can increase runtime cost. Constructors, class initializers, and large methods are excluded. Control flow and virtualization automatically trigger frame recomputation; external dependencies may be needed on the classpath.

Existing `tableswitch` and `lookupswitch` branches (including Kotlin `when`) can also participate in flattening. Methods rejected because of stack-carrying boundaries or reused local-slot types now fall back to shuffled basic blocks connected by direct JVM branches. No interpreter, boxing, helper calls or state dispatch is added by this fallback. Exception handlers are supported by splitting protected ranges at block boundaries while preserving handler priority; constructors and oversized methods remain excluded; annotation and class exclusions still apply.

For speed-sensitive ordinary code, use `--lightweight-control-flow` (Gradle/core: `lightweightControlFlow = true`). This enables the direct-branch layout transformation alone, overriding keyed dispatch even with strong protection. It changes physical block order without changing conditions or operand-stack transfers. Added jumps and code layout can still affect JIT compilation and throughput; near-original speed and decompiler failure are not guaranteed. Modern decompilers can reconstruct these blocks. Benchmark the actual workload before deployment; this option does not disable separately selected virtualization, string encryption or numeric obfuscation. `@HotPath` continues to suppress body transformations.

## Non-VM call indirection

`--call-indirection` (Gradle/core: `callIndirection = true`) replaces eligible static, virtual and interface calls with `invokedynamic`. Each site stores encoded owner, member and descriptor strings with its own key. A generated Java 8 helper resolves the target with the original caller's lookup and installs a `ConstantCallSite`. Decoding and method lookup occur during linkage, not on every invocation; the JVM can optimize the linked handle. This adds cold-linkage work and can affect warm performance. It is obfuscation, not a cryptographic boundary: an analyst can recover the keys and resolved handles.

The pass runs after renaming, so encoded references use final names. It requires application roots (`applicationPackages` or discovered entrypoints) and leaves bundled dependency bodies alone. Constructors, class initializers, synthetic methods, special invocations, array owners, signature-polymorphic calls and selected caller-sensitive APIs are skipped. Body policy annotations and class exclusions apply. Existing lambdas and string-concatenation bootstrap sites are preserved. The generated helper only needs the JDK; no interpreter or Obfaryn runtime dependency is shipped separately. Custom pipelines must keep this pass after all renaming and relocation. Calls can remain direct when encoded strings, code size or constant-pool growth would exceed class-file limits.

For a Paper fat JAR, start with this configurable non-VM setup:

```kotlin
obfaryn {
  platform = "paper"
  applicationPackages += "com.example.plugin"
  protection = "strong"
  lightweightControlFlow = true
  callIndirection = true
  flattenPackages = true
  targetPackage = "com.example.plugin"
}
```

Use `@HotPath` to preserve performance-sensitive bodies and `@Virtualize` only where interpreter costs are acceptable. Without virtualization annotations or explicit selectors, this configuration does not virtualize methods. For stronger flow transformations at additional runtime cost, set `lightweightControlFlow = false`; strong protection then enables keyed dispatch where eligible. `transformers { ... }` builds a list and does not register it with the Gradle plugin merely by appearing inside `obfaryn { ... }`. Use `pipeline { replace("control-flow", ControlFlowTransformer(Random(42))) }` to replace an enabled pass; use `add` for new IDs. CLI output, JSON reports and Gradle logs include per-pass metrics such as `indirectedCalls`, `rearrangedMethods` and `dispatchedMethods`.

To disable existing `@Virtualize` selections for a non-VM comparison, pass `--exclude-virtualization ".*"`. In Gradle, use `virtualize { excludeMethods = listOf(".*") }` with `@file:OptIn(com.peco2282.obfaryn.gradle.VirtualizeOptIn::class)` at the top of the build script. Remove that exclusion to restore annotation-based selection.

Paper does not infer performance sensitivity from arbitrary method names such as `tick` or `onTick`. Its automatic virtualization exclusions use event annotations and actual scheduler/lifecycle contracts. Application-specific hot paths belong in annotations or explicit configuration. Core transformations have no Paper-specific name tests.

## Strong protection

Enable stronger application-scoped transformations in Gradle:

```kotlin
obfaryn {
  protection = "strong"
  applicationPackages += "com.example.plugin"
  platform = "paper" // Requires the Paper provider on the build tool classpath
}
```

The CLI equivalent is `obfaryn input.jar --protection strong --application-package com.example.plugin --classpath dependency.jar`. The core API uses `protectionLevel = ProtectionLevel.STRONG`. The default remains `standard`.

Strong protection enables control-flow transformations and ASM output validation, and recomputes frames. It does not automatically select methods for virtualization. Use `@Virtualize` for explicit selection; existing method-pattern settings remain supported. It requires explicit application roots or roots discovered from platform/manifest entrypoints. Bundled classes outside those roots are excluded from body and name transformations; references to renamed application types are still updated. Literal settings and Kotlin metadata policies remain configurable. Package flattening is configured separately.

Strong protection writes `<output-name>.virtualization.json` beside the output, unless `virtualizationReport` supplies another path. Explicit selections retain the configured selection mode, including strict failures for unsupported methods. Zero virtualized methods is valid. `@HotPath` and user exclusions can exclude methods from virtualization.

Paper applies conservative performance exclusions to method-pattern selections by default. Explicit `@Virtualize` annotations override these Paper exclusions. To disable the exclusions for pattern selections, set `platformOptions["virtualization-protect-hot-paths"] = "false"` in Gradle or pass `--platform-option virtualization-protect-hot-paths=false` to the CLI. This removes Paper's automatic lifecycle, event, scheduled-task, and transitive-call exclusions; entrypoint name and descriptor protection still applies. User exclusions and source `@HotPath` directives still apply. This can substantially increase tick costs; measure a representative workload before deployment.

Constructors, monitors, and other unsupported patterns can remain readable. Review the report for each sensitive method. VM conversion adds execution and memory costs; validate on a representative server workload. JVM validity or a decompiler failure alone does not prove equivalent behavior or resistance to analysis. Publish the output JAR without the mapping and analysis reports, which contain original names.

Two optional JDK 21 helpers support validation:

- `java -Xverify:all scripts/VerifyClasses.java output.jar dependencies.txt com.example.plugin` loads classes without initialization and checks their declared members. `dependencies.txt` contains one dependency path per line; use the destination package after relocation. This does not exercise plugin lifecycle or gameplay.
- `java -cp "obfaryn-cli/build/install/obfaryn/lib/*" scripts/RemapTestClasses.java output.mapping.txt original-test-classes remapped-test-classes` updates compiled test references using the output mapping. Run the same tests against the original JAR and the remapped tests against the output JAR with its runtime dependencies. Test assertions and method bodies are preserved apart from symbol references; string-based reflection needs separate handling.

`java -Xmx512m -cp "obfaryn-cli/build/install/obfaryn/lib/*" scripts/BenchmarkStringLiterals.java` compares legacy XOR decoding, rolling-state decoding, and cached rolling-state literal reads on generated Java 8 classes. Build `:obfaryn-cli:installDist` first and run with JDK 21. It reports warm-read latency and thread allocations; it does not measure startup, cold misses, full transformation pipelines, or application performance.

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

Paper excludes lifecycle callbacks, `@EventHandler`, `Runnable`/`BukkitRunnable.run()V`, and methods they call within the JAR. The analysis report (schema version 2) lists policy exclusions and reasons. Use `@HotPath` or an explicit exclusion for other frequently called methods.

The compiler translates supported bytecode into a typed, block-based IR; its verifier checks locals, branches, stack bounds, value kinds, and merge points. The output JAR contains interpreters and integer-ID bridges for selected field, invocation, cast, array, and constructor operations. Obfaryn itself is not needed at runtime. Supported values include int-like, reference, float, long, double, and void returns. Arrays, branches, loops, dense and sparse switches, class literals, typed exception handlers, finally blocks, throws, and selected calls are supported. Method-body constructors, class initializers, monitors, arbitrary uninitialized objects and legacy subroutines are not virtualized. Only straight-line `NEW → DUP → arguments → invokespecial <init>` constructor calls meeting safety checks can become bridges.

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

- Retained Kotlin metadata is rewritten for supported formats; unsupported metadata fails before output. Test reflection and metadata-dependent frameworks in the target runtime.
- Reflection protection uses resolved declaring classes rather than global member-name matches. Unresolved reflective member targets produce a warning and require explicit keep rules. Names stored in configuration may also require explicit keep rules. Paper/manifest entrypoints and ServiceLoader names receive built-in protection.
- Multi-release JAR versioned classes are retained only when Obfaryn can rule out references to renamed application packages; otherwise processing fails.
- VM support covers selected bytecode patterns, not every JVM instruction. VM hardening, performance budgets, and resistance evaluation remain future work.
- Integrity protection detects changes according to its signed manifest; a self-contained JAR cannot guarantee that modification or reverse engineering is impossible.

### Customizing the built-in pipeline

```kotlin
val obfuscator = Obfuscator(
  config = ObfuscationConfig.lightweight().copy(seed = 42),
  configurePipeline = {
    add(MyTransformer())
    // replace("rename", MyRenameTransformer()) // retain the same ID
    // disable("rename")
  },
)
val result = obfuscator.obfuscate(input, output)
println(result.transformerReports)
```

`lightweight()` disables string and number body transformations while retaining renaming
and metadata cleanup. Existing constructor defaults remain unchanged. Pipeline edits apply
to fresh built-in passes on each invocation; disabled configuration options do not create passes.
Duplicate IDs, unknown replacement/removal IDs, missing `Transformer.requires` dependencies,
and dependency cycles fail before passes execute. Dependencies take priority over numeric
`order`; otherwise ordering uses `order` then ID. Custom passes can return metrics and warnings
from `report()`. Built-in result counters and mapping output describe built-in passes only;
a custom renamer must provide its own mapping. The existing full-list constructor remains available.

`@HotPath` now excludes string, number, control-flow, and virtualization body transformations.
On a class it also prevents constant-field extraction. A method-level `@Virtualize` can override
a class-level `@HotPath`. Platform callback detection continues to exclude virtualization only.
# Extending transformations

Custom passes can be registered directly in the Gradle DSL. The block runs against a fresh registry for each execution; construct stateful transformers inside it.

```kotlin
import com.peco2282.obfaryn.api.transformer.*

obfaryn {
  pipeline {
    add(MyTransformer())
    // disable("number-obfuscation")
    // replace("string-encryption", MyStringTransformer()) // retain the ID
  }
}
```

Passes declare mandatory predecessors with `requires`, and optional ordering with `before` / `after`. Missing optional IDs are ignored; all ordering cycles are rejected before execution. Numeric `order` breaks ties between ready passes. Set `requiresFrameRecomputation = true` when a custom pass needs new stack map frames; supply dependency JARs through `classpath` when needed.

Use `context.forEachTransformableMethod { owner, method -> ... }` to visit a snapshot of methods permitted by the body policy, and `owner.uniqueMethodName("helper")` to find an unused helper name. These helpers do not validate arbitrary bytecode edits or register runtime dependencies. Generated methods must still have valid bodies and stack/local limits.

Mix numeric generators and sample eligible sites without replacing literal discovery:

```kotlin
import com.peco2282.obfaryn.api.transformer.*
import com.peco2282.obfaryn.transformer.*

obfaryn {
  number {
    strategy = WeightedNumberObfuscationStrategy(listOf(
      WeightedNumberStrategy(DefaultNumberObfuscationStrategy(), 3),
      WeightedNumberStrategy(MyNumberStrategy(), 1),
    ))
    selector = DefaultNumberObfuscationSelector() and NumberObfuscationSelector.probability(0.75)
  }
}
```

`MyTransformer`, `MyStringTransformer` and `MyNumberStrategy` above are user implementations. Weights must be positive; the selected strategy returning `null` skips the constant without retrying. Float/double overrides are preserved. Selection uses the supplied random source and is reproducible with a fixed seed and unchanged configuration. More variants alone do not guarantee stronger resistance to analysis.
