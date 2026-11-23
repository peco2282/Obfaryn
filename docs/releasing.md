# Preparing a release

Build releases from the reviewed `main` branch using JDK 21 and the checked-in Gradle wrapper.
Keep local handoffs, mappings, reports and build outputs out of commits.

## Prepare the version and notes

1. Compare the changes with the previous release tag. Update `releaseVersion` in
   `gradle.properties`, the Obfaryn coordinates in both READMEs, and the matching version section
   in `CHANGELOG.md`. Do not update an application's own example `implementationVersion`.
2. Describe user-visible additions, changes, fixes and upgrade requirements. Check source and
   binary compatibility separately; Kotlin data-class constructor changes can require consumers
   of the embedding API to recompile even when source calls still compile.
3. Extract the exact text that will become the GitHub Release description:

   ```bash
   python3 -m unittest discover -s scripts/tests -p 'test_release_notes.py'
   python3 scripts/release-notes.py v1.2.0 --check-project-version --output build/release-notes.md
   ```

   The extractor rejects missing, duplicate or empty version sections. The project-version check
   rejects a tag that differs from `releaseVersion`. Release notes come from the reviewed section,
   without automatically appending commit-history notes.

## Validate the artifacts

```bash
bash gradlew build :obfaryn-cli:installDist :obfaryn-cli:distTar :obfaryn-cli:distZip
python3 scripts/verify-cli-distribution.py
python3 scripts/verify-custom-extension.py
python3 scripts/verify-apply-mapping.py
python3 scripts/verify-springboot-extension.py
python3 scripts/verify-mc-mod-extension.py
python3 scripts/verify-jpms-module.py
python3 scripts/report-summary.py --self-test
python3 scripts/residual-leak-scan.py --self-test
```

Run these scripts with JDK 21 on `PATH`. Verify the generated distribution filenames and inspect
their contents. Test loading each newly shipped platform provider through the installed CLI.
Platform unit tests and stub-based extension fixtures do not establish live loader/server behavior.
Use the documented public-project, framework-runtime and JVM-matrix checks when relevant to the
changes; record their actual scope rather than treating local tests as remote CI results.

## Publish after approval

Commit the preparation and wait for the verification workflow on the intended release commit.
Create and push the matching `v<version>` tag only when release publication is authorized:
tag pushes trigger `.github/workflows/release.yml`, which publishes the CLI archives and reviewed
Changelog section. The new workflow requires the tagged checkout to contain `scripts/release-notes.py`.
Rebuilding an older tag that predates the script requires its original release workflow.

Publishing the GitHub Release triggers the separate Maven Central workflow. It uploads a signed
`USER_MANAGED` deployment; complete that deployment in the Central Portal after reviewing it.
Local preparation, tests and archive creation do not publish artifacts. Never replace a previously
released artifact with different bytes under the same version.
