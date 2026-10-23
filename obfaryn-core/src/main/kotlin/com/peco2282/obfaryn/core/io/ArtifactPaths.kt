package com.peco2282.obfaryn.core.io

import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

/** File identity for collision checks, including future paths. This does not lock the filesystem. */
object ArtifactPaths {
  @JvmStatic
  fun sameFile(a: Path, b: Path): Boolean = resolved(a) == resolved(b) ||
    Files.exists(a) && Files.exists(b) && Files.isSameFile(a, b)

  private fun resolved(path: Path): Path {
    // Resolve links before collapsing '..'; lexical normalization can identify the wrong file.
    var absolute = path.toAbsolutePath()
    val visited = hashSetOf<Path>()
    while (true) {
      var parent = absolute
      while (!Files.exists(parent, LinkOption.NOFOLLOW_LINKS) && parent.parent != null) parent = parent.parent
      if (!Files.isSymbolicLink(parent)) return parent.toRealPath().resolve(parent.relativize(absolute)).normalize()
      require(visited.add(parent.toAbsolutePath().normalize())) { "Cyclic symbolic link in artifact path: $path" }
      val target = Files.readSymbolicLink(parent)
      absolute = (if (target.isAbsolute) target else parent.parent.resolve(target))
        .resolve(parent.relativize(absolute))
    }
  }
}
