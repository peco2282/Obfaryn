# Obfaryn optional runtime API

A Java 8 module with no external dependencies. It contains `ProtectionRuntime.prelink` and an internal linkage template. Compile against this artifact when calling the optional API; enable `prelinkCalls` when producing a classpath JAR to embed the API and generated linker. The obfuscation engine is not a runtime dependency.

See [English protection controls](../docs/protection.md) or [日本語の保護設定](../docs/protection.ja.md) for lifecycle effects, bounded ranges, failures and limitations. Prelinking does not execute target methods. Invoking a generated entry can initialize its caller, and actual JVM linkage/JIT costs remain on first use.
