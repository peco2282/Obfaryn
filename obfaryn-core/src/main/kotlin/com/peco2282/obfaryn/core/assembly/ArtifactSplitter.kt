package com.peco2282.obfaryn.core.assembly

import com.peco2282.obfaryn.api.context.ResourceEntry
import com.peco2282.obfaryn.core.context.ObfuscationContext
import com.peco2282.obfaryn.core.jar.JarWriter
import org.objectweb.asm.commons.ClassRemapper
import org.objectweb.asm.commons.Remapper
import org.objectweb.asm.tree.ClassNode
import java.nio.file.Path

/**
 * Splits a renamed [ProgramUnion] back into one output JAR per artifact.
 *
 * After the whole-program rename, every class in [union] carries its new name while
 * [ObfuscationContext.originalClassNames] still maps it to its pre-rename name. Combined with
 * [ProgramUnion.origins], that decides which artifact each renamed class belongs to. Each artifact's
 * own resources stay local; only its `module-info` descriptor is rewritten here, applying the global
 * class and package mappings so its `exports`/`opens` follow classes that moved.
 *
 * @param union the renamed union context.
 * @param origins original internal class name to artifact id, from [ProgramUnion.origins].
 * @param classMapping the renamer's global original-to-new class mapping.
 * @param packageMapping the renamer's global original-to-new package mapping.
 */
class ArtifactSplitter(
  private val union: ObfuscationContext,
  private val origins: Map<String, String>,
  private val classMapping: Map<String, String>,
  private val packageMapping: Map<String, String>,
  private val jarWriter: JarWriter = JarWriter(),
) {
  private val moduleRemapper = object : Remapper() {
    override fun map(internalName: String): String = classMapping[internalName] ?: internalName
    override fun mapPackageName(name: String): String = packageMapping[name] ?: name
  }

  /** The renamed classes belonging to [artifactId], keyed by their new internal name. */
  fun classesFor(artifactId: String): Map<String, ClassNode> =
    union.classes.filterKeys { newName ->
      origins[union.originalClassNames[newName] ?: newName] == artifactId
    }

  /**
   * Writes [artifactId]'s output JAR: its renamed classes, its [resources], and its [moduleInfo]
   * descriptor remapped with the global mappings.
   *
   * @param moduleInfo the artifact's original `module-info` node, or `null` when it has none.
   * @param recomputeFrames recompute StackMap frames, using [classpath] to resolve external
   *   supertypes; pass `true` when a body pass that needed it ran on this artifact.
   */
  fun write(
    artifactId: String,
    resources: Map<String, ResourceEntry>,
    moduleInfo: ClassNode?,
    output: Path,
    reproducible: Boolean,
    verifyOutput: Boolean,
    recomputeFrames: Boolean,
    classpath: List<Path>,
  ) {
    val classes = LinkedHashMap(classesFor(artifactId))
    if (moduleInfo != null) {
      val remapped = ClassNode()
      moduleInfo.accept(ClassRemapper(remapped, moduleRemapper))
      classes[remapped.name] = remapped
    }
    val context = ObfuscationContext(classes, LinkedHashMap(resources))
    jarWriter.write(context, output, reproducible, verifyOutput, recomputeFrames, classpath)
  }
}
