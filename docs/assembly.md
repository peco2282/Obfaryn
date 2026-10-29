# Assembling Obfaryn

[日本語](assembly.ja.md)

The engine and the standard distribution are separate dependencies. Extensions can be built outside this repository with **only `obfaryn-api`**. There is no central list of permitted user packages, transformer classes, or custom feature IDs.

| Module / contract | Responsibility |
| --- | --- |
| `obfaryn-api` | Transformer, recipe, platform, mapping, policy and lifecycle contracts |
| `obfaryn-core` | Archive I/O, assembly, ordering, identity tracking, budgets, verification and reports; no dependency on `obfaryn-transformer` or `obfaryn-standard` |
| `obfaryn-transformer` | Optional standard transformations and builders |
| `obfaryn-standard` | Standard recipe, renaming, virtualization, integrity tools and the configured `Obfuscator` facade |
| Platform provider | Environment detection, compatibility rules, generated content and dedicated recipes |
| CLI / Gradle | User configuration and explicit extension selection |

Existing `com.peco2282.obfaryn.core.Obfuscator`, configuration, renamer and VM type names are retained, but those specialized classes now live in **`obfaryn-standard`**. Consumers using them must change their dependency from `obfaryn-core` to `obfaryn-standard`. The core engine is `TransformationEngine`.

## Define a recipe in your own project

Depend on `com.peco2282.obfaryn:obfaryn-api:<version>` using your configured Maven repository. Define a transformer implementing `Transformer`, then group parts with:

```kotlin
import com.peco2282.obfaryn.api.assembly.TransformationPackage

val myPackage = TransformationPackage { assembly, pipeline ->
  // Construct stateful parts here: the recipe may be reused across runs.
  pipeline.add(MyTransformer(assembly.random))
}
```

A recipe can add, disable and replace passes through the same `TransformerRegistry`. The host validates unique IDs, required dependencies and cycles after all recipes and final edits. Duplicate IDs are errors; packages do not silently override one another. `before` / `after` describe optional ordering; `requires` also requires the other part to exist.

A launcher depends on core and the extension, and optionally on the standard distribution:

```kotlin
import com.peco2282.obfaryn.core.EngineOptions
import com.peco2282.obfaryn.core.TransformationEngine
import java.nio.file.Paths

val engine = TransformationEngine(EngineOptions(
  applicationPackages = listOf("com.example"),
  seed = 42L,
))
engine.transform(Paths.get("input.jar"), Paths.get("output.jar"), packages = listOf(myPackage))
```

With the generic platform and no additional platforms, an empty list runs **no transformation passes**. Selected platforms may supply their own recipes. Archive normalization, signature removal and optional verification still belong to I/O; an empty list does not promise byte-identical ZIP output. To include standard parts, add `StandardPackage()` from `com.peco2282.obfaryn.standard`. The standard `Obfuscator` facade preserves its configured default recipe; `packages = emptyList()` overrides that recipe, while `pipelineConfigurers` make final edits.

`EngineOptions` controls host scope, classpath, policies, budgets, verification and output. `StandardPackage(ObfuscationConfig(...))` selects standard transformations and their algorithms. For the complete legacy configuration surface, analysis-only execution and file-based mapping/report destinations, use the configured `Obfuscator` facade in `obfaryn-standard`.

## Discover an extension JAR

Implement `TransformationPackageProvider` with a public no-argument constructor, a stable `id`, and `create(options)`. Register its class name in:

```text
META-INF/services/com.peco2282.obfaryn.api.assembly.TransformationPackageProvider
```

Installing a provider does not execute its recipe. Hosts explicitly select IDs with `TransformationPackages.select(...)`. Providers must have unique, nonblank IDs; `none` is reserved. Provider creation receives string options; runtime assembly receives the input, shared configuration, application roots, classpath and seeded random source.

The [custom extension example](../examples/custom-extension/src/main/kotlin/example/extension/ExamplePackage.kt) compiles against API alone. It defines a namespaced body feature, respects method policy, participates in class-atomic budgets, and also exposes a platform that supplies the same recipe. It does not depend on core, transformer or standard implementations.

## CLI

```shell
obfaryn input.jar -o output.jar --extension my-extension.jar \
  --no-default-package --package my-package --platform-option rounds=2
```

`--extension` adds a host-side JAR for the duration of the invocation; repeat it for additional extension dependencies. These JARs do not enter the application output. `--package` adds an explicitly selected recipe. Without `--no-default-package`, configured standard parts are registered first. Adding a second recipe with the same pass IDs is an error, including explicitly adding `standard` on top of the default recipe. Both the default invocation and `obfuscate` support these flags.

Use `--platform paper --also-platform my-framework` to compose platform behavior in explicit order. A tied primary automatic detection still fails; secondary platforms must be named explicitly. Contributions reject duplicate classes/resources, conflicting annotation policies fail, and duplicate transformer IDs fail before passes execute.

## Gradle

```kotlin
dependencies {
  obfarynPlatform(files("my-extension.jar"))
}
obfaryn {
  useDefaultPackage = false
  packageIds = listOf("my-package")
  additionalPlatforms = listOf("my-framework")
  pipeline {
    // Final edits after user and platform packages have registered their parts.
  }
}
```

Groovy uses `packageIds = ['my-package']` and `useDefaultPackage = false`. `obfarynPlatform` loads both platform and package providers without adding them to application compile/runtime dependencies. A build/convention plugin can supply a recipe directly with `transformationPackage(myPackage)` instead of service discovery. Create stateful transformers inside each recipe or `pipeline` callback.

## Dedicated tools and advanced contracts

A platform returns `PlatformPackages { context, options -> listOf(myPackage) }` from `extensions()`. Content contributions run before recipe assembly. Execution order is explicit user recipes, selected platforms' recipes, then final pipeline configurers. `EngineOptions.additionalPlatforms` accepts directly defined platform objects; `CompositePlatform` also supports library composition. All selected platforms' content is contributed before their annotation aliases are applied, including aliases targeting another selected platform's contributed classes.

For a custom renamer, implement `RenamingTransformer` and publish original-input class/member mappings. The pass itself must consult `context.namePreservation` and rewrite affected references. The API exposes live class/member/resource name requirements, including platform rules and rules added by later recipes. Additional hierarchy and reflection safety remain the renamer's responsibility. The engine uses mappings to retain original method identities even when methods are reordered or descriptors change, preserve application scope after renaming, and expose mapping text. At most one renaming pass is supported per run; a replacement need not use the built-in renamer class.

For a custom budgeted body pass, use `BodyFeature("my-tool:feature")` as `isolatedBodyFeature`, check `canTransformBody(owner, method, feature)` before edits, and provide `transformedCount` for the most recent invocation. There is no global feature registration. Custom features use the same original-name rules, rollback and method reports as built-in features. API rules accept `ProtectionRule(pattern, mapOf(feature to false))`; Gradle accepts `rules { method("com/example/.*") { feature("my-tool:feature", false) } }`; CLI accepts `--disable-feature 'my-tool:feature=com/example/.*'` (custom CLI IDs require a colon). Built-in annotations retain their built-in properties; general body restrictions and structural budgets still apply to custom features.

Recipes may call `excludeClass`, `excludeResource`, and `protectInput` on `AssemblyContext`. Exclusions hide entries from ordinary pass enumeration and preserve their names; direct dependency lookups remain available. `protectInput` rejects aliases of host artifact destinations, using the same identity checks as other inputs. An extension that writes additional files must validate those destinations itself.

Output tools register `OutputProcessor` through assembly. `beforeWrite` runs after passes, including on dry runs; `afterWrite` runs only after the JAR has been written. Hooks run in registration order. Integrity sealing is supplied by the standard recipe through this contract. Arbitrary extension code is trusted code; these contracts are not a security sandbox, filesystem lock or multi-artifact transaction.

## Validation

With JDK 21:

```shell
bash gradlew test :obfaryn-cli:installDist :examples:custom-extension:jar
python3 scripts/verify-cli-distribution.py
python3 scripts/verify-custom-extension.py
```

Core tests deliberately omit standard/transformer implementations. The external-style sample tests cover custom budgets and rules, third-party renaming and lifecycle hooks. TestKit exercises Kotlin and Groovy provider selection. The distribution script runs a Java 8 fixture using the installed CLI plus an explicitly loaded API-only extension, then executes only the output JAR with `-Xverify:all`. These are JVM checks, not live Paper validation or proof of universal platform compatibility.
