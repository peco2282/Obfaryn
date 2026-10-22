package com.peco2282.obfaryn.core.rename

import com.peco2282.obfaryn.api.ObfuscationContext
import com.peco2282.obfaryn.api.context.ResourceEntry
import com.peco2282.obfaryn.api.transformer.Transformer
import com.peco2282.obfaryn.core.ObfuscationConfig
import com.peco2282.obfaryn.core.annotation.ObfuscationAnnotationPolicy
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
data class MemberKey(val owner: String, val name: String, val desc: String)

/**
 * Plans and applies class, method, and field renames while honoring keep and hierarchy rules.
 *
 * Virtual methods are planned as override families; external API overrides and unresolved
 * families are retained. Package flattening pins packages whose access or relative resource
 * behavior cannot safely survive relocation. Mapping tables retain original identities.
 */
class RenameTransformer(private val config: ObfuscationConfig, private val keep: KeepRules) : Transformer {
  override val id = "rename"
  override val order = 40
  val classMapping = linkedMapOf<String, String>()
  val methodMapping = linkedMapOf<MemberKey, String>()
  val fieldMapping = linkedMapOf<MemberKey, String>()
  val warnings = mutableListOf<String>()

  override fun transform(context: ObfuscationContext) {
    val nodes = context.classes.values.toList()
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
          keep.method(node.name, method.name, method.desc) || ObfuscationAnnotationPolicy.keepName(method) ||
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
        if (!config.renameFields || keep.field(node.name, field.name) || ObfuscationAnnotationPolicy.keepName(field) || protectedFieldOwner(node) ||
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
    keep.className(node.name) || ObfuscationAnnotationPolicy.keepName(node) || node.name.endsWith("/package-info") ||
      node.name == "module-info" || protectedStructure(node, keepEnum = false, keepAnnotationType = false)

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

  private fun protectedStructure(node: ClassNode, keepEnum: Boolean = true, keepAnnotationType: Boolean = true) =
    node.access and (Opcodes.ACC_RECORD or (if (keepEnum) Opcodes.ACC_ENUM else 0) or
      (if (keepAnnotationType) Opcodes.ACC_ANNOTATION else 0)) != 0 ||
      node.interfaces.any { it == "java/io/Serializable" || it == "java/io/Externalizable" } ||
      node.methods.any { it.access and Opcodes.ACC_NATIVE != 0 }

  private fun objectMethod(name: String, desc: String) = when (name to desc) {
    "toString" to "()Ljava/lang/String;", "hashCode" to "()I", "equals" to "(Ljava/lang/Object;)Z",
    "clone" to "()Ljava/lang/Object;", "finalize" to "()V" -> true
    else -> false
  }

  fun mappingText(): String = buildString {
    for ((old, new) in classMapping) appendLine("$old -> $new")
    for ((key, new) in methodMapping) appendLine("  ${key.owner}.${key.name}${key.desc} -> $new")
    for ((key, new) in fieldMapping) appendLine("  ${key.owner}.${key.name}:${key.desc} -> $new")
  }
}
