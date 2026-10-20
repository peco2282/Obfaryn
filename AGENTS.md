# Repository Guidelines

## Project Structure & Module Organization

Obfaryn is an ASM-based obfuscator for Kotlin/JVM and Java JARs. Gradle modules separate responsibilities:

- `obfaryn-api`: platform and transformer interfaces.
- `obfaryn-annotations`: transformation policy annotations.
- `obfaryn-core`: obfuscation engine and runtime helpers.
- `obfaryn-transformer`: standard transformations and builders.
- `obfaryn-cli`: Picocli command-line application.
- `obfaryn-gradle-plugin`: Gradle integration.
- `platforms/paper`: Paper platform provider and cache support.

Sources follow `src/main/kotlin` and, where needed, `src/main/java`. Tests live in `src/test/kotlin`; service registrations and fixtures use `src/main/resources` or `src/test/resources`. Shared dependency versions live in `gradle/libs.versions.toml`.

## Build, Test, and Development Commands

Use JDK 21 and the checked-in Gradle wrapper (`gradlew.bat` on Windows).

- `./gradlew build`: compile, test, and assemble the project.
- `./gradlew test`: run the test suites.
- `./gradlew :obfaryn-core:test`: run engine tests only.
- `./gradlew :obfaryn-gradle-plugin:test`: run Gradle integration tests.
- `./gradlew :obfaryn-cli:run --args='--help'`: run the CLI locally.
- `./gradlew :obfaryn-cli:installDist`: generate the runnable distribution under `obfaryn-cli/build/install/obfaryn`.

Preserve Java 8 bytecode compatibility for library and CLI modules; the Gradle plugin targets Java 17.

## Coding Style & Naming Conventions

Follow `.editorconfig`: UTF-8, LF endings, final newlines, and two-space indentation for `.kt` and `.kts` files. Batch scripts use CRLF. Use `UpperCamelCase` for types, `lowerCamelCase` for functions and properties, and packages under `com.peco2282.obfaryn`. Match surrounding code; no dedicated formatter or lint task is configured.

## Testing Guidelines

Use JUnit Jupiter; plugin integration tests also use Gradle TestKit. Name test classes/files `*Test` and use descriptive test methods. Add regression tests for transformation changes, checking both bytecode validity and runtime behavior where relevant. Use temporary directories for generated JARs. No numeric coverage threshold is configured.

## Commit & Pull Request Guidelines

Recent commits use descriptive imperative subjects such as `Add ...`, `Remove ...`, and `Refactor: ...`. Keep commits focused. In pull requests, explain the behavior change, affected modules, and validation commands/results; link related issues when applicable. Update both `README.md` and `README.ja.md` for documented user-facing changes.
