package com.peco2282.obfaryn.core.rename

import com.peco2282.obfaryn.api.ObfuscationContext
import com.peco2282.obfaryn.api.context.ResourceEntry
import com.peco2282.obfaryn.api.transformer.MappingFormat
import com.peco2282.obfaryn.api.transformer.MappingSource
import com.peco2282.obfaryn.api.transformer.NameMapping
import com.peco2282.obfaryn.api.transformer.NamePreservation
import com.peco2282.obfaryn.api.transformer.RenamingTransformer
import com.peco2282.obfaryn.api.transformer.TransformerReport
import com.peco2282.obfaryn.api.transformer.MemberKey as ApiMemberKey
import java.nio.file.Path
import kotlin.io.path.readText
import com.peco2282.obfaryn.core.ObfuscationConfig
import com.peco2282.obfaryn.core.annotation.ObfuscationAnnotationPolicy
import com.peco2282.obfaryn.core.mapping.MappingDigest
import com.peco2282.obfaryn.core.keep.KeepRules
import com.peco2282.obfaryn.core.jar.ClassHierarchyResolver
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import org.objectweb.asm.commons.ClassRemapper
import org.objectweb.asm.commons.Remapper
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.FieldNode
import org.objectweb.asm.tree.FieldInsnNode
import org.objectweb.asm.tree.MethodInsnNode

/** Original member identity used for mapping and reference rewriting. */
typealias MemberKey = ApiMemberKey

/**
 * Plans and applies class, method, and field renames while honoring keep and hierarchy rules.
 *
 * Virtual methods are planned as override families; external API overrides and unresolved
 * families are retained. Package flattening pins packages whose access or relative resource
 * behavior cannot safely survive relocation. Mapping tables retain original identities.
 */
class RenameTransformer(private val config: ObfuscationConfig, private val keep: KeepRules) : RenamingTransformer {
  override val id = "rename"
  override val order = 40
  override val classMapping = linkedMapOf<String, String>()
  override val methodMapping = linkedMapOf<MemberKey, String>()
  override val fieldMapping = linkedMapOf<MemberKey, String>()
  override val packageMapping = linkedMapOf<String, String>()
  val warnings = mutableListOf<String>()
  private var names = NamePreservation.NONE
  /** Provenance of applied (re-used) mappings, carried into this run's output for chained apply. */
  private var importedSources: List<MappingSource> = emptyList()

  override val transformedCount: Int get() = classMapping.size + methodMapping.size + fieldMapping.size
  override fun report() = TransformerReport(id, warnings = warnings.toList())

  override fun transform(context: ObfuscationContext) {
    names = context.namePreservation
    val nodes = context.classes.values.toList()
    // Capture shared rules at execution time, including rules added by later recipes.
    for (owner in nodes) {
      if (names.keepsClassName(owner)) keep.keepClassNameOnly(owner.name)
      owner.methods.filter { names.keepsMethodName(owner, it) }.forEach { keep.keepMethod(owner.name, it.name, it.desc) }
      owner.fields.filter { names.keepsFieldName(owner, it) }.forEach { keep.keepField(owner.name, it.name) }
    }
    val metadata = KotlinMetadataRemapper.prepare(nodes, context.resources.mapValues { it.value.data })
    KotlinMemberConstraints.protect(nodes, keep)
    val defaultHelpers = nodes.flatMap { owner -> owner.methods.mapNotNull { helper ->
      KotlinMemberConstraints.defaultTarget(owner, helper)?.let { target ->
        MemberKey(owner.name, helper.name, helper.desc) to target
      }
    } }.toMap()
    val descendants = nodes.flatMap { n -> (listOfNotNull(n.superName) + n.interfaces).map { it to n.name } }
      .groupBy({ it.first }, { it.second })
    val generatedByPackage = mutableMapOf<String, NameGenerator>()
    val generatedByOuter = mutableMapOf<String, NameGenerator>()
    val occupied = nodes.map { it.name }.toMutableSet()
    // apply-mapping: inject the renames of separately-obfuscated artifacts (named by config.appliedMappings,
    // present on the classpath) as fixed assignments before allocation, so references to them resolve to
    // the names they were actually given and this run's own names avoid them. Those classes must not also
    // be inputs of this run. Their provenance is carried into this run's output for chained apply.
    if (config.appliedMappings.isNotEmpty()) {
      val applied = loadAppliedMappings(config.appliedMappings)
      verifyAppliedSources(applied.sources, config.classpath)
      val collisions = applied.classes.keys.intersect(occupied)
      require(collisions.isEmpty()) {
        "apply-mapping: class(es) ${collisions.sorted()} appear in both an applied mapping and this run's input"
      }
      classMapping.putAll(applied.classes)
      methodMapping.putAll(applied.methods)
      fieldMapping.putAll(applied.fields)
      packageMapping.putAll(applied.packages)
      occupied += applied.classes.values
      importedSources = applied.sources
    }
    val target = config.targetPackage?.replace('.', '/').takeIf { config.flattenPackages }
    val applicationPackages = config.applicationPackages.map { it.replace('.', '/').trimEnd('/') + "/" }.toSet()
    fun isInApplicationPackage(name: String) = applicationPackages.any { name.startsWith(it) }
    val pinnedPackages = if (target == null) emptySet() else nodes.filter { node ->
      protectedClass(node) || (listOfNotNull(node.superName) + node.interfaces).any {
        it !in context.classes && it.substringBeforeLast('/', "") == node.name.substringBeforeLast('/', "") &&
          // If the reference target is also in an application package, it will be relocated together,
          // so it should not pin the package.
          !isInApplicationPackage(it)
      } || node.methods.any { method ->
        (method.access and (Opcodes.ACC_PUBLIC or Opcodes.ACC_PROTECTED or Opcodes.ACC_PRIVATE) == 0 &&
          !method.name.startsWith('<') &&
          // Package-private methods in application packages don't pin the package because all classes
          // in the package will be relocated together.
          !isInApplicationPackage(node.name)) || method.instructions.toArray().any { insn ->
          insn is MethodInsnNode &&
            ((insn.owner == "java/lang/Class" && insn.name in setOf("getResource", "getResourceAsStream")) ||
              (insn.owner !in context.classes && insn.owner.substringBeforeLast('/', "") == node.name.substringBeforeLast('/', "") &&
                !isInApplicationPackage(insn.owner))) ||
            insn is FieldInsnNode && insn.owner !in context.classes &&
              insn.owner.substringBeforeLast('/', "") == node.name.substringBeforeLast('/', "") &&
              !isInApplicationPackage(insn.owner)
        }
      } || node.fields.any {
        it.access and (Opcodes.ACC_PUBLIC or Opcodes.ACC_PROTECTED or Opcodes.ACC_PRIVATE) == 0 &&
          // Captured outer instances (this$0) are synthetic package-private fields.
          // Their accesses move together with the enclosing Kotlin class hierarchy.
          !capturedKotlinOuter(node, it) &&
          // Package-private fields in application packages don't pin the package.
          !isInApplicationPackage(node.name)
      }
    }.map { it.name.substringBeforeLast('/', "") }.toSet()
    for (pkg in pinnedPackages.sorted()) warnings += "Package relocation preserved $pkg: kept structure, package access, or relative resource lookup"
    val kotlinOuters = nodes.filter(keep::kotlinClass).mapNotNull { node ->
      node.innerClasses.firstOrNull { it.name == node.name && it.outerName != null && it.innerName != null }
        ?.let { node.name to (it.outerName to it.innerName) }
    }.toMap()
    val companionNames = kotlinOuters.filterKeys(metadata::isCompanionClass).values
      .groupBy({ it.first }, { it.second })
    val keptKotlinOuters = mutableSetOf<String>()
    for (node in nodes.filter(::protectedClass)) {
      var current = kotlinOuters[node.name]?.first
      while (current != null && keptKotlinOuters.add(current)) current = kotlinOuters[current]?.first
    }
    for (node in nodes.sortedBy { it.name.count { c -> c == '$' } }) {
      if (!config.renameClasses || protectedClass(node) || node.name in keptKotlinOuters) continue
      val nesting = kotlinOuters[node.name]
      if (nesting != null) {
        val outer = classMapping[nesting.first] ?: nesting.first
        val candidate = if (metadata.isCompanionClass(node.name)) {
          // Kotlin reflection resolves the companion instance through its named static field.
          "$outer\$${nesting.second}"
        } else {
          val generator = generatedByOuter.getOrPut(outer) { SequentialNameGenerator() }
          var name: String
          do { name = "$outer\$${generator.next()}" }
          while (name in occupied || name.substringAfterLast('$') in companionNames[nesting.first].orEmpty())
          name
        }
        if (candidate != node.name) {
          require(candidate !in occupied) { "Kotlin nested class relocation collides with $candidate" }
          classMapping[node.name] = candidate
          occupied += candidate
        }
        continue
      }
      val originalPackage = node.name.substringBeforeLast('/', "")
      val pkg = if (originalPackage in pinnedPackages) originalPackage else target ?: originalPackage
      val generator = generatedByPackage.getOrPut(pkg) { SequentialNameGenerator() }
      var candidate: String
      do { candidate = (if (pkg.isEmpty()) "" else "$pkg/") + generator.next() } while (candidate in occupied)
      classMapping[node.name] = candidate
      occupied += candidate
    }

    // module-info exports/opens name whole packages; only publish a package move when every
    // original member actually relocated to the same target, never a partial/best-effort guess.
    if (target != null) {
      for ((originalPackage, members) in nodes.groupBy { it.name.substringBeforeLast('/', "") }) {
        if (originalPackage.isEmpty() || originalPackage == target || originalPackage in pinnedPackages) continue
        val relocated = members.filter { it.name in classMapping }
        if (relocated.isEmpty()) continue
        if (relocated.size == members.size && relocated.all { classMapping.getValue(it.name).substringBeforeLast('/', "") == target }) {
          packageMapping[originalPackage] = target
        } else {
          warnings += "Package relocation left $originalPackage split across the original and $target packages: " +
            "module-info exports/opens for $originalPackage were not rewritten"
        }
      }
    }

    val families = ClassHierarchyResolver(context, config.classpath).use { hierarchy ->
      VirtualMethodFamilies(nodes, hierarchy, keep, classMapping).apply { if (config.renameMethods) plan() }
    }
    methodMapping.putAll(families.declarations)

    for (node in nodes) {
      val methodNames = node.methods.map { it.name }.toMutableSet()
      methodNames += node.methods.mapNotNull { methodMapping[MemberKey(node.name, it.name, it.desc)] }
      methodNames += node.methods.map { it.name.removeSuffix("\$default") }
      val methodGenerator = SequentialNameGenerator()
      for (method in node.methods) {
        val virtual = method.access and (Opcodes.ACC_PRIVATE or Opcodes.ACC_STATIC) == 0
        if (MemberKey(node.name, method.name, method.desc) in defaultHelpers || !config.renameMethods || virtual || method.name.startsWith('<') || method.access and Opcodes.ACC_NATIVE != 0 ||
          keep.method(node.name, method.name, method.desc) || names.keepsMethodName(node, method) || ObfuscationAnnotationPolicy.keepName(method) ||
          protectedMembers(node) || objectMethod(method.name, method.desc)) continue
        if (node.name !in classMapping && method.access and (Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC) == (Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC)) continue
        if (method.name == "main" && method.desc == "([Ljava/lang/String;)V") continue
        var candidate: String
        do { candidate = methodGenerator.next() } while (candidate in methodNames)
        methodMapping[MemberKey(node.name, method.name, method.desc)] = candidate
        methodNames += candidate
      }
      val fieldNames = node.fields.map { it.name }.toMutableSet()
      val fieldGenerator = SequentialNameGenerator()
      for (field in node.fields) {
        if (!config.renameFields || keep.field(node.name, field.name) || names.keepsFieldName(node, field) || ObfuscationAnnotationPolicy.keepName(field) || protectedFieldOwner(node) ||
          field.access and Opcodes.ACC_ENUM != 0 ||
          (field.access and Opcodes.ACC_PRIVATE == 0 && descendants[node.name].orEmpty().isNotEmpty()) ||
          field.name == "serialVersionUID" ||
          (field.access and (Opcodes.ACC_PUBLIC or Opcodes.ACC_PROTECTED) != 0 &&
            !safeStaticConstantField(node.name, field.access, field.desc) &&
            !safeCompanionField(field.name, field.access, field.desc))) continue
        var candidate: String
        do { candidate = fieldGenerator.next() } while (candidate in fieldNames)
        fieldMapping[MemberKey(node.name, field.name, field.desc)] = candidate
        fieldNames += candidate
      }
    }

    for ((helper, target) in defaultHelpers) {
      if (keep.method(helper.owner, helper.name, helper.desc)) continue
      val renamed = methodMapping[target] ?: families.inheritedReferences[target] ?: continue
      methodMapping[helper] = renamed + "\$default"
    }

    val remapper = object : Remapper() {
      override fun map(internalName: String): String = classMapping[internalName] ?: internalName
      // Drives module-info's exports/opens directives via ASM's ModuleRemapper; requires/uses/
      // provides/main-class already follow map() above without any override needed here.
      override fun mapPackageName(name: String): String = packageMapping[name] ?: name
      override fun mapMethodName(owner: String, name: String, descriptor: String): String =
        methodMapping[MemberKey(owner, name, descriptor)] ?: families.inheritedReferences[MemberKey(owner, name, descriptor)] ?: name

      override fun mapInvokeDynamicMethodName(name: String, descriptor: String): String {
        val result = Type.getReturnType(descriptor)
        return if (result.sort == Type.OBJECT) families.dynamicName(result.internalName, name) ?: name else name
      }
      override fun mapFieldName(owner: String, name: String, descriptor: String): String = fieldMapping[MemberKey(owner, name, descriptor)] ?: name
    }
    for (node in nodes) {
      val mapped = ClassNode()
      node.accept(ClassRemapper(mapped, remapper))
      KotlinMemberConstraints.pinJvmNames(node, mapped)
      metadata.rewrite(mapped, classMapping, methodMapping, fieldMapping, node.name)
      context.removeClass(node.name)
      context.putClass(mapped)
    }
    for ((name, data) in metadata.rewriteModules(classMapping)) context.putResource(ResourceEntry(name, data))
  }

  private fun capturedKotlinOuter(node: ClassNode, field: FieldNode): Boolean =
    keep.kotlinClass(node) && field.name == "this\$0" && field.access and Opcodes.ACC_SYNTHETIC != 0 &&
      node.innerClasses.any { it.name == node.name && it.outerName != null && field.desc == "L${it.outerName};" }

  private fun protectedClass(node: ClassNode): Boolean =
    keep.className(node.name) || names.keepsClassName(node) || ObfuscationAnnotationPolicy.keepName(node) || node.name.endsWith("/package-info") ||
      node.name == "module-info" || protectedStructure(node, keepEnum = false, keepAnnotationType = false, keepRecord = false)

  private fun protectedMembers(node: ClassNode) = protectedStructure(node)

  private fun protectedFieldOwner(node: ClassNode) =
    node.access and (Opcodes.ACC_ANNOTATION or Opcodes.ACC_RECORD) != 0 ||
    node.interfaces.any { it == "java/io/Serializable" || it == "java/io/Externalizable" } ||
    node.methods.any { it.access and Opcodes.ACC_NATIVE != 0 }

  private fun safeStaticConstantField(owner: String, access: Int, desc: String) =
    owner in classMapping && access and (Opcodes.ACC_STATIC or Opcodes.ACC_FINAL) == (Opcodes.ACC_STATIC or Opcodes.ACC_FINAL) &&
      desc in setOf("Z", "B", "C", "S", "I", "J", "F", "D", "Ljava/lang/String;")

  private fun safeCompanionField(name: String, access: Int, desc: String) =
    name == "Companion" && access and (Opcodes.ACC_STATIC or Opcodes.ACC_FINAL) == (Opcodes.ACC_STATIC or Opcodes.ACC_FINAL) &&
      desc.startsWith("L") && desc.endsWith(";") && desc.substring(1, desc.length - 1) in classMapping

  // Record component fields, accessors and ObjectMethods bootstrap labels must stay aligned.
  // Retain those members, but allow class relocation: ASM remaps their types and bootstrap handles.
  private fun protectedStructure(
    node: ClassNode, keepEnum: Boolean = true, keepAnnotationType: Boolean = true, keepRecord: Boolean = true,
  ) =
    node.access and ((if (keepRecord) Opcodes.ACC_RECORD else 0) or (if (keepEnum) Opcodes.ACC_ENUM else 0) or
      (if (keepAnnotationType) Opcodes.ACC_ANNOTATION else 0)) != 0 ||
      node.interfaces.any { it == "java/io/Serializable" || it == "java/io/Externalizable" } ||
      node.methods.any { it.access and Opcodes.ACC_NATIVE != 0 }

  private fun objectMethod(name: String, desc: String) = when (name to desc) {
    "toString" to "()Ljava/lang/String;", "hashCode" to "()I", "equals" to "(Ljava/lang/Object;)Z",
    "clone" to "()Ljava/lang/Object;", "finalize" to "()V" -> true
    else -> false
  }

  /**
   * Fails if any artifact an applied mapping was built against is missing from the classpath or
   * present with a different digest — a stale re-apply that would otherwise diverge silently.
   */
  private fun verifyAppliedSources(sources: List<MappingSource>, classpath: List<Path>) {
    for (source in sources) {
      val match = classpath.firstOrNull { it.fileName?.toString() == source.identifier }
      requireNotNull(match) {
        "apply-mapping: '${source.identifier}', which an applied mapping was built against, is not on the classpath to verify"
      }
      val actual = MappingDigest.of(match)
      require(actual == source.digest) {
        "apply-mapping: '${source.identifier}' on the classpath (digest $actual) does not match the applied " +
          "mapping (expected ${source.digest}); the mapping is stale for this build of the artifact"
      }
    }
  }

  /**
   * Parses and merges the applied mapping files into one forward model. Entries that agree are
   * merged (a shared upstream mapping may legitimately reach here through more than one applied
   * file — the diamond-dependency case); an entry that assigns the same original identity a
   * different name, or a source identifier that carries a different digest, is a conflict and fails.
   */
  private fun loadAppliedMappings(paths: List<Path>): NameMapping {
    val classes = LinkedHashMap<String, String>()
    val methods = LinkedHashMap<MemberKey, String>()
    val fields = LinkedHashMap<MemberKey, String>()
    val packages = LinkedHashMap<String, String>()
    val digestByIdentifier = LinkedHashMap<String, String>()

    fun <K> merge(target: MutableMap<K, String>, additions: Map<K, String>, kind: String) {
      for ((key, value) in additions) {
        val existing = target.put(key, value)
        require(existing == null || existing == value) {
          "apply-mapping: conflicting $kind mapping for '$key' across applied mappings ('$existing' vs '$value')"
        }
      }
    }

    for (path in paths) {
      val model = MappingFormat.parse(path.readText())
      merge(classes, model.classes, "class")
      merge(methods, model.methods, "method")
      merge(fields, model.fields, "field")
      merge(packages, model.packages, "package")
      for (source in model.sources) {
        val existing = digestByIdentifier.put(source.identifier, source.digest)
        require(existing == null || existing == source.digest) {
          "apply-mapping: conflicting digests for source '${source.identifier}' across applied mappings " +
            "('$existing' vs '${source.digest}'): they describe different builds of the same artifact"
        }
      }
    }
    return NameMapping(classes, methods, fields, packages, digestByIdentifier.map { MappingSource(it.key, it.value) })
  }

  // Renders this run's tables (including any applied fixed assignments, so the output is a self-contained
  // merged mapping) plus the accumulated provenance of the artifacts those assignments came from, through
  // MappingFormat — the single authority for the textual format.
  override fun mappingText(): String =
    MappingFormat.render(classMapping, methodMapping, fieldMapping, packageMapping, importedSources)
}
