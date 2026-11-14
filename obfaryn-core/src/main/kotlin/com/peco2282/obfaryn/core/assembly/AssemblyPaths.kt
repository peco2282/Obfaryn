package com.peco2282.obfaryn.core.assembly

import com.peco2282.obfaryn.core.io.ArtifactPaths
import java.nio.file.Files
import java.nio.file.Path

/**
 * Set-wise destination validation for an assembly.
 *
 * A single-artifact run only has to keep its one output off its inputs, classpath and keys. An
 * assembly writes several JARs at once, so every output must additionally differ from every other
 * output and from every artifact's input. This reuses [ArtifactPaths.sameFile], so hard links,
 * symbolic links, `..` after a link and not-yet-existing destinations are all compared by resolved
 * identity. These are collision checks, not filesystem locking or a multi-artifact transaction.
 */
object AssemblyPaths {
  /**
   * Fails unless every path in [outputs] is distinct from the others and from each protected path.
   *
   * @param outputs the artifacts' destination JARs, labeled for diagnostics (e.g. the artifact id).
   * @param protectedInputs inputs, classpath entries and integrity keys that no output may overwrite.
   * @throws IllegalArgumentException on the first collision found, or if an output is a directory.
   */
  fun validate(outputs: List<Pair<String, Path>>, protectedInputs: List<Path>) {
    for ((index, destination) in outputs.withIndex()) {
      val (label, path) = destination
      require(!Files.isDirectory(path)) { "Output JAR for $label must not refer to a directory" }
      require(protectedInputs.none { ArtifactPaths.sameFile(it, path) }) {
        "Output JAR for $label must not overwrite an input, classpath entry or integrity key"
      }
      require(outputs.take(index).none { ArtifactPaths.sameFile(it.second, path) }) {
        "Output JAR for $label must not overwrite another artifact's output"
      }
    }
  }
}
