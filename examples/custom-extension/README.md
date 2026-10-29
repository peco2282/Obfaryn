# API-only extension example

This module depends on `obfaryn-api` alone. Its source may be copied into an independent build using the published API coordinates; no source changes to core or transformer are necessary.

`ExamplePackageProvider` exposes reversible integer padding as `example-padding`. `ExamplePlatformProvider` exposes an explicitly selectable `example` platform that supplies that same recipe and keeps its framework entrypoint. The custom `example:padding` feature participates in host policy and structural budgets.

```shell
bash gradlew :obfaryn-cli:installDist :examples:custom-extension:jar
python3 scripts/verify-custom-extension.py
```

For configuration and lifecycle contracts, see [Assembly](../../docs/assembly.md) / [組み立て](../../docs/assembly.ja.md).
