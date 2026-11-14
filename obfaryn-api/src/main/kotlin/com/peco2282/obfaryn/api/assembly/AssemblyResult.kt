package com.peco2282.obfaryn.api.assembly

import com.peco2282.obfaryn.api.transformer.TransformerReport
import java.nio.file.Path

/**
 * The outcome of one cross-artifact assembly run.
 *
 * The rename decision is made once for the whole program, so the session produces a single
 * cross-artifact mapping even though it writes one JAR per artifact. Writing several JARs is not a
 * single transaction: a failure can leave only some of them written.
 */
interface AssemblyResult {
  /** The destination JAR written for each artifact, keyed by [AssemblyArtifact.stableId]. */
  val outputs: Map<String, Path>

  /**
   * The single cross-artifact mapping covering every renamed class, method, field and package
   * across all artifacts, in the same text form a single-artifact run emits.
   */
  val mapping: String

  /** The transformation reports produced for each artifact, keyed by [AssemblyArtifact.stableId]. */
  val reports: Map<String, List<TransformerReport>>
}
