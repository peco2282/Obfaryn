# API stability policy (draft for 1.0.0)

[日本語](api-stability.ja.md)

This is a proposal, not yet a settled decision — it needs the maintainer's sign-off before the
first 1.0.0 tag. Its purpose: once 1.0.0 ships, "is this change breaking?" should have one
answer everyone can check against, instead of being re-litigated per pull request.

## What 1.0.0 promises (semver-breaking requires a major bump)

- **CLI flags and their behavior** (`obfaryn-cli`): removing a flag, changing its default,
  narrowing what it accepts, or changing what it does with existing input is breaking. Adding a
  new flag, or a new value a flag accepts, is not.
- **Gradle DSL** (`obfaryn-gradle-plugin`'s `obfaryn { ... }` block): removing or renaming a
  property, changing its type, or changing a documented default is breaking. Adding a property or
  a nested block is not.
- **`obfaryn-api` extension interfaces**: `Transformer`, `RenamingTransformer`,
  `TransformationPackage`/`TransformationPackageProvider`, `Platform`/`PlatformProvider`,
  `PipelineConfigurer`, `NumberObfuscationStrategy`, `StringEncryptionCodec`, and the annotation
  types in `obfaryn-annotations`. Removing a method, changing a signature, or changing documented
  semantics (e.g. what `canTransformBody` consults, when `transformedCount` is read) is breaking.
  Adding a method with a default implementation is not (the project already does this — see the
  Kotlin `DefaultImpls` compatibility regression test).
- **The standard facade's public constructor/method surface**: `Obfuscator`, `ObfuscationConfig`,
  `ObfuscationResult` (in `obfaryn-standard`) for anyone embedding the engine directly instead of
  through the CLI or Gradle plugin.
- **Published Maven coordinates**: `groupId`/`artifactId` for every module in the table below stay
  fixed; a module is not renamed or merged into another without a major bump.

## What is explicitly not covered (free to change in any release)

- Everything inside `obfaryn-core`, `obfaryn-standard`, and `obfaryn-transformer` that is not one
  of the public surfaces above: internal class names, internal method signatures, the exact set
  and order of internal passes, internal file layout.
- CLI output formatting (human-readable summary lines) beyond the stable `--report`/
  `--transformation-report` JSON schema version already declared in `docs/protection.md`.
- Anything explicitly marked experimental or `@VirtualizeOptIn` at the time of the 1.0.0 tag, until
  that marking is lifted.
- Default *values* of structural budgets, growth allowances, and similar tuning constants — their
  existence and type are stable; the number itself may still be tuned.
- Performance characteristics (timing, allocation) are never a stability promise; only documented
  behavior is.

## Module stability tiers

| Module | Tier | Why |
| --- | --- | --- |
| `obfaryn-api` | Stable surface | Extension authors compile against this directly |
| `obfaryn-annotations` | Stable surface | Appears in consumers' own source |
| `obfaryn-runtime` | Stable surface | Embedded into end-user output JARs; a behavior change there ships inside someone else's app |
| `obfaryn-cli` | Stable surface (flags only) | User-facing contract |
| `obfaryn-gradle-plugin` | Stable surface (DSL only) | User-facing contract |
| `obfaryn-standard` | Partially stable | `Obfuscator`/`ObfuscationConfig`/`ObfuscationResult` are stable; everything else inside is not |
| `obfaryn-core` | Internal | No external consumer is expected to depend on it beyond what `obfaryn-api` re-exposes |
| `obfaryn-transformer` | Internal | Same |
| `obfaryn-platform-paper` | Stable surface (provider contract only) | External platform providers implement `obfaryn-api`'s `PlatformProvider`; this module is the reference implementation |

## Process going forward

1. A pull request that changes anything in the "what 1.0.0 promises" list gets called out as
   breaking in its description, even pre-1.0.0 — this builds the habit before it's load-bearing.
2. After 1.0.0, a breaking change to a stable surface requires either a major version bump or an
   explicit, documented deprecation window (old API kept working, marked `@Deprecated`, for at
   least one minor release before removal).
3. `obfaryn-api/src/test/kotlin/.../BinaryPolicyCompatibilityTest.kt`'s pattern (compile against
   an older interface shape, load against the current one) is the mechanical check for the
   "adding a method is not breaking" rule above; extend it when a new stable interface gains a
   method.
