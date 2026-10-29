# Obfaryn API: writing your first extension

[日本語](README.ja.md) · [Project guide](../README.md)

`obfaryn-api` defines the contracts shared by the execution engine and extensions. It exposes ASM tree nodes but does **not** load JARs or execute a pipeline itself. Start with a `Transformer` to change bytecode/resources, or a `Platform` to detect a framework and supply keep rules and generated content.

## 1. Dependencies and source layout

Build this repository with JDK 21 and the checked-in Gradle wrapper. API consumers should use a compatible Kotlin compiler (this project uses language/API version 2.1). Library bytecode targets Java 8; the Gradle plugin targets Java 17.

For an extension module included in this checkout's `settings.gradle.kts`, use:

```kotlin
plugins {
  kotlin("jvm")
}

dependencies {
  implementation(project(":obfaryn-api"))
}
```

ASM core and tree types are exposed transitively by the API. Put Kotlin files under `src/main/kotlin/com/example/extension/`. A separate **launcher** module that executes the example below also needs `implementation(project(":obfaryn-standard"))` and a dependency on your extension module. Do not add core to an extension merely to implement an interface.

For a separate local build, first run `./gradlew :obfaryn-api:publishToMavenLocal` in this repository, then use `mavenLocal()` and `mavenCentral()` with `implementation("com.peco2282.obfaryn:obfaryn-api:0.0.0-SNAPSHOT")`. This is the current local publication coordinate, not a promise of a Maven Central release. Publishing the API alone does not publish the engine.

## 2. A complete Transformer

This deliberately small pass replaces only the string literal `Hello` with `Welcome`. It is an example of a behavior change, **not** an encryption algorithm. Save it as `GreetingTransformer.kt`:

```kotlin
package com.example.extension

import com.peco2282.obfaryn.api.ObfuscationContext
import com.peco2282.obfaryn.api.transformer.Transformer
import com.peco2282.obfaryn.api.transformer.TransformerReport
import org.objectweb.asm.tree.LdcInsnNode

class GreetingTransformer : Transformer {
  override val id = "example-greeting"
  override val order = -100
  private var replacements = 0L

  override fun transform(context: ObfuscationContext) {
    replacements = 0
    for (owner in context.classes.values) {
      for (method in owner.methods) {
        if (!context.transformationPolicy.canTransformBody(owner, method)) continue
        for (instruction in method.instructions.toArray()) {
          if (instruction is LdcInsnNode && instruction.cst == "Hello") {
            instruction.cst = "Welcome"
            replacements++
          }
        }
      }
    }
  }

  override fun report() = TransformerReport(
    id = id,
    metrics = mapOf("replacements" to replacements),
  )
}
```

The policy check respects host exclusions and annotation policies; mutable ASM nodes do not enforce this automatically. `toArray()` gives a stable instruction traversal when adding/removing instructions. This replacement leaves the JVM stack shape unchanged and adds no control-flow edges. More advanced rewrites must preserve stack types, locals, exception ranges, and stack map frames.

`order = -100` is a preference to run early, before standard string encryption. For a hard dependency use `requires = setOf("other-pass-id")`: a smaller order cannot override a dependency. Missing dependencies and cycles fail before any pass executes. Equal-order ties use the ID, not registration order.

## 3. Register and run from a launcher

Save this in the launcher module as `Main.kt`, alongside the dependency on the extension:

```kotlin
package com.example.launcher

import com.example.extension.GreetingTransformer
import com.peco2282.obfaryn.api.transformer.PipelineConfigurer
import com.peco2282.obfaryn.core.ObfuscationConfig
import com.peco2282.obfaryn.core.Obfuscator
import java.nio.file.Paths

fun main() {
  val extension = PipelineConfigurer {
    add(GreetingTransformer())
  }
  val result = Obfuscator(
    config = ObfuscationConfig.lightweight(),
    pipelineConfigurers = listOf(extension),
  ).obfuscate(Paths.get("input.jar"), Paths.get("output.jar"))
  println(result.transformerReports)
}
```

Run this `main` from IntelliJ IDEA with the launcher module's runtime classpath and a working directory containing `input.jar`. Input and output must differ. `lightweight()` disables standard string/number body passes but retains renaming and metadata cleanup. Test the transformed application, not just the successful return from `obfuscate`.

Create passes **inside** the callback: a fresh registry is built for each run, but callbacks may be reused. `add` requires a unique non-blank ID. `replace(id, pass)` requires an existing pass and the same ID on the replacement. `disable(id)` requires an existing pass and does not disable its dependants. Inspect optional passes with `this[id]` before replacing/disabling them. A replacement `rename` implements `RenamingTransformer`, consults `context.namePreservation`, rewrites all affected references, and publishes original-input mappings.

Implementing a `Transformer` does not make the CLI discover it automatically. Pass it to the host explicitly through `PipelineConfigurer`; this is distinct from platform provider service discovery.

### Budgeted body extensions

For a class-local body pass, opt in with `Transformer.isolatedBodyFeature`. The host supplies one candidate class, dependency lookup for reading and buffered helper injection. Check `context.transformationPolicy.canTransformBody(owner, method, feature)` before editing each method. The opt-in does not automatically make arbitrary edits obey feature exclusions; the pass must use the policy.

Do not mutate dependency nodes returned by lookup, mutate resources or remove classes in this mode. The host commits or rolls back the candidate and injected helpers together, validates cumulative growth and code size, and reports injected helper methods too. A generated helper class uses its own policy identity and the run budget as its default; it does not inherit the originating method's budget automatically. Leave `isolatedBodyFeature` null for global passes needing other semantics.

Use `context.transformationInputs.methodIdentity(owner, method)` for original-name diagnostics. `skipReason` must not mutate nodes or consume randomness. Reset pass counters on every `transform` call: the host can invoke a budgeted pass once per candidate class. Class-node lookup remains cooperative mutable access, not a security sandbox.

See [protection controls](../docs/protection.md) for rule precedence, budgets, helper reports and prelink lifecycle effects, and [implementation status](../docs/implementation-status.md) for the validated scope and extension limits.

## 4. Contexts, names, and resources

| API | Use |
| --- | --- |
| `ObfuscationContext` | Inspect mutable class nodes; add, replace, or remove classes/resources during a pass. |
| `PlatformContext` | Inspect inputs for platform detection/configuration; use contributor sinks for new content. |
| `TransformationInputs` | Ask which classes are application code, which constants are kept, and where Kotlin metadata should be stripped. |
| `TransformationPolicy` | Check permission separately for method bodies, metadata, and constant-field conversion. |
| `ResourceEntry` | Archive-relative path and raw bytes; byte arrays are shared, not copied. |

Common name formats:

| Meaning | Example |
| --- | --- |
| JVM internal class name | `com/example/Main` |
| Dotted class name | `com.example.Main` |
| Annotation/type descriptor | `Lcom/example/Keep;` |
| Method descriptor | `(Ljava/lang/String;I)V` means String and int arguments, void return |
| Resource path | `META-INF/services/com.example.Service` |

Do not put `.class` on context class keys. A method descriptor identifies an overload; it is not a method name. Keep-rule APIs accept dotted/internal owners, while context lookups use internal names.

Inside `transform`, a resource can be added without core:

```kotlin
context.putResource(
  com.peco2282.obfaryn.api.context.ResourceEntry(
    "example/build-info.txt",
    "Generated by my extension\n".toByteArray(Charsets.UTF_8),
  ),
)
```

`putResource` replaces the same path. Use `getResource` for nullable lookup and `removeResource` for deletion. When editing an existing text resource, explicitly choose its encoding. `entry.copy(data = entry.data.copyOf())` makes an independent byte array; plain `copy()` does not. Snapshot `classes.values.toList()` before adding/removing classes during iteration. Changing `ClassNode.name` alone neither reindexes the map nor updates references elsewhere: class renaming requires a full remapping strategy.

## 5. Framework integration with Platform

Implement these responsibilities separately:

1. `PlatformProvider`: publish metadata and create a platform. Register its public no-argument implementation in `src/main/resources/META-INF/services/com.peco2282.obfaryn.api.PlatformProvider`, one fully qualified provider class name per line. The provider JAR must be on the **host's runtime classpath**.
2. `Platform.detect(context)`: inspect distinguishing resources/classes and return a confidence and useful reasons. Avoid mutating input during detection.
3. `entrypoints(context, options)`: return internal class names, not method names. Entrypoints help establish application package roots.
4. `configure(context, options, target)`: supply preservation rules. For example, inside this callback:

```kotlin
target.keepClassNameOnly("com.example.Plugin")
target.keepMethod("com.example.Plugin", "start", "()V")
target.keepResource("example-plugin.properties")
```

`keepClass` keeps class **and member names**; `keepClassNameOnly` only keeps the class name in the core implementation. Neither generally disables body transformations. `excludeMethodFromVirtualization` suppresses virtualization, not all body passes. Use an annotation policy when body permission is the actual requirement.

5. `extensions()`: return supported capabilities: `AnnotationPolicyProvider`, `ResourceContributor`, `ClassContributor`, or `ArchiveContributor`. Implementing the `PlatformExtension` marker alone has no effect. Contributors run before transformation; policies can cover contributed classes. Use the supplied sink instead of mutating inspection maps.

For an end-to-end production implementation, read [PaperPlatformProvider](../platforms/paper/src/main/kotlin/com/peco2282/obfaryn/platform/paper/PaperPlatformProvider.kt) and [PaperPlatform](../platforms/paper/src/main/kotlin/com/peco2282/obfaryn/platform/paper/PaperPlatform.kt). The API KDoc includes provider, detection, keep-rule, and contributor snippets.

## 6. Custom literal algorithms

- `NumberObfuscationSelector.shouldTransform` selects a site after body permission is checked. Inspect it without mutation; use the supplied `Random` for deterministic sampling.
- `NumberObfuscationStrategy.generateInt/generateLong` return fresh, non-empty `InsnList`s, or `null` to skip a literal. Leave exactly one value of the original JVM type without disturbing existing stack/locals. Float/double defaults preserve raw bits through integer/long generation.
- `StringEncryptionCodec.encrypt` returns a `StringEncryptionResult(payload, key)`. `generateDecryptor` emits a static `(Ljava/lang/String;I)Ljava/lang/String;` method that reconstructs the exact original string. Local slots 0/1 contain payload/key. Preserve the supplied method signature/access and set stack/local limits and any required frames.

These implementations can depend on API alone, but selecting them in standard passes requires `obfaryn-transformer` in the host. See the [standard builder examples](../README.md#transformer-extension-api). Generated runtime bytecode must reference only classes available in the output/runtime; the codec/strategy implementation is not automatically bundled. A payload and its key in the same JAR are not a cryptographic secrecy guarantee.

## 7. Validation and API reference

Before using a custom pass in production:

1. Transform a tiny fixture JAR and verify changed and unchanged cases (including excluded/annotated methods).
2. Check bytecode with the host's `verifyOutput = true` configuration (CLI: `--verify-output`); supply dependencies for hierarchy/frame analysis when needed.
3. Execute the output and compare behavior. Cover empty/Unicode strings, numeric limits, NaNs and signed zero for literal algorithms.
4. Run two fresh instances with the same seed to check reproducibility. Reset reports per invocation and return snapshots of mutable diagnostics.

KDoc on each API type is the detailed reference. From the repository root, generate the configured Javadoc publication with:

```shell
./gradlew :obfaryn-api:dokkaGeneratePublicationJavadoc
./gradlew :obfaryn-api:dokkaJavadocJar
```

The second task packages the generated documentation under `obfaryn-api/build/libs/`. These commands generate documentation; they do not validate extension runtime behavior.
