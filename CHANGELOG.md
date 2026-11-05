# Changelog

Format loosely follows [Keep a Changelog](https://keepachangelog.com/). No tags exist in this
repository yet; versions below are correlated with `gradle.properties`' `releaseVersion` at the
time of the "Bump to" commit, not with a Git tag.

## [Unreleased]

Committed on `main`, not yet published to `https://maven.peco2282.com/maven-releases/` under any
version. The last `publish` ran at `0.2.0` (see below) **before** these landed.

### Fixed

- `module-info.class`'s `exports`/`opens` directives and its concealed-package list are now
  rewritten to track relocation under `--flatten-packages`. Previously they stayed at their
  original package name even when every class in that package moved, leaving a descriptor that
  claimed the wrong location. `uses`/`provides`/the module's main class already followed renamed
  classes correctly before this fix, independent of flattening. `requires` directives and the
  module's own declared name are still never rewritten, by design.

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

### Added

- `scripts/verify-jpms-module.py`, run in CI: builds a real `module-info`-bearing fixture,
  transforms it with `--flatten-packages`, and confirms the descriptor is correct and the output
  runs both on the classpath and on the module path (`java -p out.jar -m <module>`).

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
