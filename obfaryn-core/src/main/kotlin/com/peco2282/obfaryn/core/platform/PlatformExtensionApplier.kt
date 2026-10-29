package com.peco2282.obfaryn.core.platform

import com.peco2282.obfaryn.api.*
import com.peco2282.obfaryn.api.context.ResourceEntry
import com.peco2282.obfaryn.core.annotation.ObfuscationAnnotationPolicy
import com.peco2282.obfaryn.core.context.ObfuscationContext
import org.objectweb.asm.ClassReader
import org.objectweb.asm.tree.AnnotationNode
import org.objectweb.asm.tree.ClassNode
import java.io.ByteArrayInputStream
import java.util.jar.JarInputStream

/** Removes only synthesized policy annotations, in reverse insertion order, when cleaned up. */
class AppliedPlatformExtensions(private val cleanupActions: List<() -> Unit>) {
  fun cleanup() = cleanupActions.asReversed().forEach { it() }
}

/** Applies platform content contributions before translating platform annotation policies. */
object PlatformExtensionApplier {
  /**
   * Mutates [context] in extension order, rejecting existing class/resource keys rather than
   * overwriting them. Contributions already applied are not rolled back if a later one fails.
   * Resource bytes are copied; class contributions are parsed into fresh ASM nodes.
   *
   * Merged archives cannot contain modules, versioned entries, or signature files, and their
   * manifests are not contributed. Embedded archives remain uninterpreted resource bytes.
   * Policy providers run after contributions so contributed classes are also covered. Their
   * aliases add temporary invisible Obfaryn annotations; the returned cleanup removes only
   * those additions, preserving original annotations and contributed content.
   */
  fun apply(platform: Platform, context: ObfuscationContext,
    options: PlatformOptions): AppliedPlatformExtensions {
    val extensions = platform.extensions().toList()
    val resources = ResourceSink { path, content ->
      validatePath(path)
      require(!path.endsWith(".class")) { "Class entries must be added through ClassContributor: $path" }
      require(!context.hasResource(path)) { "Platform resource already exists: $path" }
      context.putResource(ResourceEntry(path, content.copyOf()))
    }
    val classes = ClassSink { bytecode ->
      val node = readClass(bytecode)
      require(!context.hasClass(node.name)) { "Platform class already exists: ${node.name}" }
      context.putClass(node)
    }
    val archives = ArchiveSink { archive, mode, path ->
      when (mode) {
        ArchiveContributionMode.EMBED_AS_RESOURCE -> {
          require(path != null) { "Embedded archive requires a resource path" }
          resources.add(path, archive)
        }

        ArchiveContributionMode.MERGE -> mergeArchive(archive, resources, classes)
      }
    }
    for (extension in extensions) when (extension) {
      is ResourceContributor -> extension.contribute(context, options, resources)
      is ClassContributor -> extension.contribute(context, options, classes)
      is ArchiveContributor -> extension.contribute(context, options, archives)
    }
    return applyAnnotationPolicies(extensions.filterIsInstance<AnnotationPolicyProvider>(), context)
  }

  private fun mergeArchive(archive: ByteArray, resources: ResourceSink, classes: ClassSink) {
    JarInputStream(ByteArrayInputStream(archive)).use { jar ->
      while (true) {
        val entry = jar.nextJarEntry ?: break
        if (entry.isDirectory) continue
        val path = entry.name
        validatePath(path)
        require(path != "module-info.class") { "Platform archive modules are unsupported" }
        require(!path.startsWith("META-INF/versions/")) { "Platform multi-release archives are unsupported" }
        require(!Regex("(?i)^META-INF/[^/]+\\.(SF|RSA|DSA|EC)$").matches(path)) {
          "Signed platform archives cannot be merged: $path"
        }
        val bytes = jar.readBytes()
        when {
          path == "META-INF/MANIFEST.MF" -> Unit
          path.endsWith(".class") -> classes.add(bytes)
          else -> resources.add(path, bytes)
        }
      }
    }
  }

  private fun applyAnnotationPolicies(providers: List<AnnotationPolicyProvider>,
    context: ObfuscationContext): AppliedPlatformExtensions {
    val policies = providers.flatMap { it.policies() }
    val grouped = policies.groupBy { it.descriptor }
    for ((descriptor, sameDescriptor) in grouped) {
      require(Regex("L[^.;\\[]+(?:/[^.;\\[]+)*;").matches(descriptor)) { "Invalid annotation descriptor: $descriptor" }
      require(sameDescriptor.map { it.action }.distinct().size == 1) {
        "Conflicting platform annotation policies for $descriptor"
      }
    }
    val cleanup = mutableListOf<() -> Unit>()
    fun apply(nodes: MutableList<AnnotationNode>?, target: AnnotationPolicyTarget,
      invisible: MutableList<AnnotationNode>?, setInvisible: (MutableList<AnnotationNode>) -> Unit) {
      val descriptors = nodes.orEmpty().mapTo(linkedSetOf()) { it.desc } + invisible.orEmpty().map { it.desc }
      for (policy in policies) {
        if (target !in policy.targets || policy.descriptor !in descriptors) continue
        val canonical = canonical(policy.action)
        if (canonical in descriptors || invisible.orEmpty().any { it.desc == canonical }) continue
        val annotation = AnnotationNode(canonical)
        val targetList = invisible ?: mutableListOf<AnnotationNode>().also(setInvisible)
        targetList += annotation
        cleanup += { targetList.remove(annotation) }
      }
    }
    for (owner in context.classes.values) {
      apply(owner.visibleAnnotations, AnnotationPolicyTarget.CLASS, owner.invisibleAnnotations) { owner.invisibleAnnotations = it }
      for (method in owner.methods) apply(method.visibleAnnotations, AnnotationPolicyTarget.METHOD,
        method.invisibleAnnotations) { method.invisibleAnnotations = it }
      for (field in owner.fields) apply(field.visibleAnnotations, AnnotationPolicyTarget.FIELD,
        field.invisibleAnnotations) { field.invisibleAnnotations = it }
    }
    return AppliedPlatformExtensions(cleanup)
  }

  private fun canonical(action: AnnotationPolicyAction): String = when (action) {
    AnnotationPolicyAction.NO_OBFUSCATION -> ObfuscationAnnotationPolicy.NO_OBFUSCATION
    AnnotationPolicyAction.RENAME_ONLY -> ObfuscationAnnotationPolicy.RENAME_ONLY
    AnnotationPolicyAction.KEEP_NAME -> ObfuscationAnnotationPolicy.KEEP_NAME
    AnnotationPolicyAction.VIRTUALIZE -> ObfuscationAnnotationPolicy.VIRTUALIZE
    AnnotationPolicyAction.HOT_PATH -> ObfuscationAnnotationPolicy.HOT_PATH
  }

  private fun readClass(bytecode: ByteArray): ClassNode {
    val reader = try { ClassReader(bytecode) } catch (cause: Exception) {
      throw IllegalArgumentException("Invalid platform class contribution", cause)
    }
    return ClassNode().also { reader.accept(it, ClassReader.EXPAND_FRAMES) }
  }

  private fun validatePath(path: String) {
    require(path.isNotBlank() && !path.startsWith('/') && !path.startsWith('\\')) { "Invalid JAR entry path: $path" }
    require(path.split('/').none { it.isEmpty() || it == "." || it == ".." }) { "Invalid JAR entry path: $path" }
  }
}
