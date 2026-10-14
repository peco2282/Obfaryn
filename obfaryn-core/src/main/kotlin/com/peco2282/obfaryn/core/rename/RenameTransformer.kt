package com.peco2282.obfaryn.core.rename

import com.peco2282.obfaryn.api.ObfuscationContext
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
    val descendants = nodes.flatMap { n -> (listOfNotNull(n.superName) + n.interfaces).map { it to n.name } }
      .groupBy({ it.first }, { it.second })
    val generatedByPackage = mutableMapOf<String, NameGenerator>()
    val occupied = nodes.map { it.name }.toMutableSet()
    val target = config.targetPackage?.replace('.', '/').takeIf { config.flattenPackages }
    val pinnedPackages = if (target == null) emptySet() else nodes.filter { node ->
      protectedClass(node) || (listOfNotNull(node.superName) + node.interfaces).any {
        it !in context.classes && it.substringBeforeLast('/', "") == node.name.substringBeforeLast('/', "")
      } || node.methods.any { method ->
        (method.access and (Opcodes.ACC_PUBLIC or Opcodes.ACC_PROTECTED or Opcodes.ACC_PRIVATE) == 0 &&
          !method.name.startsWith('<')) || method.instructions.toArray().any { insn ->
          insn is org.objectweb.asm.tree.MethodInsnNode &&
            ((insn.owner == "java/lang/Class" && insn.name in setOf("getResource", "getResourceAsStream")) ||
              (insn.owner !in context.classes && insn.owner.substringBeforeLast('/', "") == node.name.substringBeforeLast('/', ""))) ||
            insn is org.objectweb.asm.tree.FieldInsnNode && insn.owner !in context.classes &&
              insn.owner.substringBeforeLast('/', "") == node.name.substringBeforeLast('/', "")
        }
      } || node.fields.any { it.access and (Opcodes.ACC_PUBLIC or Opcodes.ACC_PROTECTED or Opcodes.ACC_PRIVATE) == 0 }
    }.map { it.name.substringBeforeLast('/', "") }.toSet()
    for (pkg in pinnedPackages.sorted()) warnings += "Package relocation preserved $pkg: kept structure, package access, or relative resource lookup"
    for (node in nodes) {
      if (!config.renameClasses || protectedClass(node)) continue
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
      val methodGenerator = SequentialNameGenerator()
      for (method in node.methods) {
        val virtual = method.access and (Opcodes.ACC_PRIVATE or Opcodes.ACC_STATIC) == 0
        if (!config.renameMethods || virtual || method.name.startsWith('<') || method.access and Opcodes.ACC_NATIVE != 0 ||
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
      context.removeClass(node.name)
      context.putClass(mapped)
    }
  }

  private fun protectedClass(node: ClassNode): Boolean =
    keep.className(node.name) || keep.kotlinClass(node) || ObfuscationAnnotationPolicy.keepName(node) || node.name.endsWith("/package-info") ||
      node.name == "module-info" || protectedStructure(node, keepEnum = false, keepAnnotationType = false)

  private fun protectedMembers(node: ClassNode) = keep.kotlinClass(node) || protectedStructure(node)

  private fun protectedFieldOwner(node: ClassNode) = keep.kotlinClass(node) ||
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
