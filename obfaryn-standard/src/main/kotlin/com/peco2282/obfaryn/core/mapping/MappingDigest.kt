package com.peco2282.obfaryn.core.mapping

import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

/**
 * Content digest of an artifact, recorded in a mapping's provenance (as a `# source` entry) and
 * recomputed when that mapping is re-applied, so a consumer built against a different build of the
 * artifact than the mapping describes fails instead of silently diverging.
 */
object MappingDigest {
  /** SHA-256 of the whole file, rendered as `sha256:<lowercase-hex>`. */
  fun of(path: Path): String {
    val bytes = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path))
    return bytes.joinToString(prefix = "sha256:", separator = "") { "%02x".format(it) }
  }
}
