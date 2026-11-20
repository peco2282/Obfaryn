# Obfaryn translation context

Obfaryn is an ASM-based obfuscator for Kotlin/JVM and Java JARs. Its public API is
split into contracts (`obfaryn-api`), policy annotations (`obfaryn-annotations`),
the archive engine (`obfaryn-core`), the configured standard facade and recipe
(`obfaryn-standard`), optional transformations (`obfaryn-transformer`), Gradle DSL
(`obfaryn-gradle-plugin`), platform-extension support (`obfaryn-platform-kit`), and
framework-specific platform providers.

Japanese API documentation is a faithful localization of English KDoc/Javadoc. It
may reorder a sentence to produce natural technical Japanese, but it must not add a
guarantee, omit a restriction, weaken a requirement, or infer behavior not stated in
the English source. If code and English documentation disagree, fix the English
source first instead of silently correcting only the Japanese translation.

Identifiers, JVM descriptors, code, link targets, enum constants, file names, and
configuration keys remain unchanged. Protected tokens such as `ZXQOBFARYN0QXZ`
stand for Dokka link or code nodes and must remain exactly intact.

