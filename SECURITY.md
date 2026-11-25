# Security Policy

## Supported Versions

Obfaryn is released on a rolling basis from `main`. Security fixes land in the latest release;
older releases do not receive backports. Pin a version from
[Maven Central](https://central.sonatype.com/namespace/com.peco2282.obfaryn) and upgrade when a
fix is published.

## Reporting a Vulnerability

Please report security issues privately rather than opening a public issue:

- Preferred: [GitHub Security Advisories](https://github.com/peco2282/Obfaryn/security/advisories/new)
  ("Report a vulnerability" under the Security tab).
- Alternative: email the maintainer listed on the [GitHub profile](https://github.com/peco2282).

Include the affected version, a reproduction case, and the impact you expect. You should get an
initial response within a few days. Confirmed issues are fixed on `main` and released; credit is
given in the release notes unless you ask otherwise.

## Scope

In scope:
- Bugs in Obfaryn's own code (CLI, Gradle plugin, core engine, standard/transformer modules,
  platform packages) that corrupt output, bypass a documented protection, leak build-time secrets
  (integrity keys, encryption keys) into the transformed artifact, or allow unintended code
  execution during a build.
- Known vulnerabilities in a dependency that actually reaches a published artifact or the build
  process (see `gradle/libs.versions.toml` and module `build.gradle.kts` files for current
  versions).

Out of scope:
- "Obfuscation can be reversed with enough effort." That is a property of obfuscation, not a
  vulnerability — see the [protection controls](docs/protection.md) and
  [assembly](docs/assembly.md) docs for what each pass actually claims. As the README states:
  obfuscation changes representation, not trust boundaries; keep secrets and authorization
  decisions outside distributed client code.
- Issues that only reproduce against a `codex/*`, experimental, or already-superseded branch.

## Dependencies

Routine dependency version bumps are handled by Dependabot
([`.github/dependabot.yml`](.github/dependabot.yml)); GitHub's Dependabot alerts flag known
vulnerabilities in resolved dependencies, including build-time-only ones (e.g. transitive to the
Dokka plugin) that never reach a published artifact.
