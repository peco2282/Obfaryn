# API-only extension example

This module is a runnable reference for an extension that depends on `obfaryn-api` alone. Its source may be copied into an independent build using the published API coordinates; no source changes to core or transformer are necessary.

## What the example demonstrates

`ExamplePackageProvider` exposes reversible integer padding as `example-padding`. `ExamplePlatformProvider` exposes an explicitly selectable `example` platform that supplies that same recipe and keeps its framework entrypoint. The custom `example:padding` feature participates in host policy and structural budgets. `ExamplePackageProvider` also illustrates a module that only needs `TransformerRegistry`, not the broader `AssemblyContext`: it simply ignores the `context` parameter it is given.

## Verify it

```shell
bash gradlew :obfaryn-cli:installDist :examples:custom-extension:jar
python3 scripts/verify-custom-extension.py
```

The script loads the extension through the installed CLI, transforms a fixture, and verifies the result independently. A successful run validates this example on the current JVM; it is not a general compatibility claim.

## Learn more

For configuration and lifecycle contracts, see [Assembly](../../docs/assembly.md) / [組み立て](../../docs/assembly.ja.md).
