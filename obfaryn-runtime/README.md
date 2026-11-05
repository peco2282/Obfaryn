# Obfaryn optional runtime API

A Java 8 module with no external dependencies. It contains `ProtectionRuntime.prelink` and an internal linkage template. Compile against this artifact when calling the optional API; enable `prelinkCalls` when producing a classpath JAR to embed the API and generated linker. The obfuscation engine is not a runtime dependency.

See [English protection controls](../docs/protection.md) or [日本語の保護設定](../docs/protection.ja.md) for lifecycle effects, bounded ranges, failures and limitations. Prelinking does not execute target methods. Invoking a generated entry can initialize its caller, and actual JVM linkage/JIT costs remain on first use.

Embedding is validated for classpath JARs; running on the module path is not separately validated. The tool rewrites `module-info`'s `exports`/`opens`/concealed-package entries under `--flatten-packages`, but never `requires` directives or the module's own declared name. See [protection controls](../docs/protection.md) ([日本語](../docs/protection.ja.md)) for release and runtime checks that remain separate from implementation.
