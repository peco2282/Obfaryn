package com.peco2282.obfaryn.api.assembly

import java.nio.file.Path

/**
 * One input artifact taking part in a cross-artifact assembly.
 *
 * An assembly renames every participating artifact in a single naming universe, so an API symbol
 * and the implementation and consumer references to it receive one consistent decision. This
 * contract describes only what the host needs to place an artifact in that universe: where its
 * bytecode comes from, where its transformed bytecode goes, and a stable identity used to route
 * renamed classes back to their originating artifact when the result is split.
 *
 * The obfuscation settings for an artifact (platform selection, body-pass choices, flags, seed)
 * are intentionally not part of this API contract; they belong to the recipe layer that drives the
 * session. Implementations pair this descriptor with those settings.
 */
interface AssemblyArtifact {
  /**
   * A stable identifier for this artifact, unique within the session.
   *
   * It is the sole basis for deciding which transformed classes are written back to this artifact,
   * so it must stay constant for the lifetime of the session and must not collide with another
   * participating artifact's identifier.
   */
  val stableId: String

  /** The input JAR for this artifact. */
  val input: Path

  /** The destination JAR for this artifact's transformed output. */
  val output: Path
}
