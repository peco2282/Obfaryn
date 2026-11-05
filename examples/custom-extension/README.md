# API-only extension example

This module depends on `obfaryn-api` alone. Its source may be copied into an independent build using the published API coordinates; no source changes to core or transformer are necessary.

`ExamplePackageProvider` exposes reversible integer padding as `example-padding`. `ExamplePlatformProvider` exposes an explicitly selectable `example` platform that supplies that same recipe and keeps its framework entrypoint. The custom `example:padding` feature participates in host policy and structural budgets. `ExampleConfigurerProvider` exposes the same kind of extension as a `PipelineConfigurerProvider` (ServiceLoader-discoverable, selected by ID with the CLI's `--configurer` or Gradle's `configurerIds`), for a module that only needs `TransformerRegistry` and not the broader `AssemblyContext`.

```shell
bash gradlew :obfaryn-cli:installDist :examples:custom-extension:jar
python3 scripts/verify-custom-extension.py
```

For configuration and lifecycle contracts, see [Assembly](../../docs/assembly.md) / [組み立て](../../docs/assembly.ja.md).
