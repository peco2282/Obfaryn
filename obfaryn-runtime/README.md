# Obfaryn optional runtime API

This Java 8 module has no external dependencies. Use it only when application code calls the optional `ProtectionRuntime.prelink` API.

## Use it

1. Compile the application against this artifact.
2. Enable `prelinkCalls` when producing a classpath JAR.
3. Run the transformed JAR without the obfuscation engine; the required API and generated linker are embedded in the output.

The module also contains an internal linkage template. That template is an implementation detail, not an application API.

## Runtime behavior and limits

See [English protection controls](../docs/protection.md) or [日本語の保護設定](../docs/protection.ja.md) for lifecycle effects, bounded ranges, failures and limitations. Prelinking does not execute target methods. Invoking a generated entry can initialize its caller, and actual JVM linkage/JIT costs remain on first use.

Embedding is validated for classpath JARs; running on the module path is not separately validated. The tool rewrites `module-info`'s `exports`/`opens`/concealed-package entries under `--flatten-packages`, but never `requires` directives or the module's own declared name.

See [protection controls](../docs/protection.md) ([日本語](../docs/protection.ja.md)) for configuration, failure behavior, and the release/runtime checks that remain separate from implementation.
