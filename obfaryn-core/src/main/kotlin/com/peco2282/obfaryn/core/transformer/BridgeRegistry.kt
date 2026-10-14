package com.peco2282.obfaryn.core.transformer

import org.objectweb.asm.ClassReader
import org.objectweb.asm.Handle
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import org.objectweb.asm.tree.*
import java.util.Random
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarFile

internal sealed interface BridgeTarget {
  data class Field(val opcode: Int, val owner: String, val name: String, val descriptor: String) : BridgeTarget
  data class Invoke(val opcode: Int, val owner: String, val name: String, val descriptor: String, val isInterface: Boolean) : BridgeTarget
  data class InvokeDynamic(val name: String, val descriptor: String, val bootstrap: Handle,
    val bootstrapArguments: List<Any>) : BridgeTarget
  data class Constructor(val owner: String, val descriptor: String) : BridgeTarget
  data class NewArray(val opcode: Int, val type: String) : BridgeTarget
  data class CheckCast(val type: String) : BridgeTarget
  data class InstanceOf(val type: String) : BridgeTarget
  data class StringConstant(val value: String) : BridgeTarget
}

internal data class BridgeEntry(val id: Int, val caller: ClassNode, val target: BridgeTarget, val signature: BridgeSignature)

internal class BridgeRegistry(private val random: Random) {
  private val entries = linkedMapOf<Pair<String, BridgeTarget>, BridgeEntry>()
  val targets: List<BridgeEntry> get() = entries.values.toList()

  fun register(caller: ClassNode, target: BridgeTarget): BridgeEntry = entries.getOrPut(caller.name to target) {
    val inputs = mutableListOf<VirtualValueKind>()
    val output: VirtualValueKind?
    when (target) {
      is BridgeTarget.Field -> {
        val type = Type.getType(target.descriptor)
        require(JvmToVirtualCompiler.supportedType(type)) { "unsupported field type ${target.descriptor}" }
        if (target.opcode in setOf(Opcodes.GETFIELD, Opcodes.PUTFIELD)) inputs.add(VirtualValueKind.REFERENCE)
        val read = target.opcode in setOf(Opcodes.GETFIELD, Opcodes.GETSTATIC)
        if (!read) inputs.add(JvmToVirtualCompiler.kind(type)!!)
        output = if (read) JvmToVirtualCompiler.kind(type) else null
      }
      is BridgeTarget.Invoke -> {
        require(target.name != "<init>" && target.name != "<clinit>") { "constructor invocation is unsupported" }
        if (target.opcode != Opcodes.INVOKESTATIC) inputs.add(VirtualValueKind.REFERENCE)
        for (type in Type.getArgumentTypes(target.descriptor)) {
          require(JvmToVirtualCompiler.supportedType(type)) { "unsupported invocation argument ${type.descriptor}" }
          inputs.add(JvmToVirtualCompiler.kind(type)!!)
        }
        val result = Type.getReturnType(target.descriptor)
        require(JvmToVirtualCompiler.supportedType(result, true)) { "unsupported invocation result ${result.descriptor}" }
        output = JvmToVirtualCompiler.kind(result)
      }
      is BridgeTarget.InvokeDynamic -> {
        for (type in Type.getArgumentTypes(target.descriptor)) {
          require(JvmToVirtualCompiler.supportedType(type)) { "unsupported invokedynamic argument ${type.descriptor}" }
          inputs.add(JvmToVirtualCompiler.kind(type)!!)
        }
        val result = Type.getReturnType(target.descriptor)
        require(JvmToVirtualCompiler.supportedType(result, true)) { "unsupported invokedynamic result ${result.descriptor}" }
        output = JvmToVirtualCompiler.kind(result)
      }
      is BridgeTarget.Constructor -> {
        for (type in Type.getArgumentTypes(target.descriptor)) {
          require(JvmToVirtualCompiler.supportedType(type)) { "unsupported constructor argument ${type.descriptor}" }
          inputs.add(JvmToVirtualCompiler.kind(type)!!)
        }
        output = VirtualValueKind.REFERENCE
      }
      is BridgeTarget.NewArray -> { inputs.add(VirtualValueKind.INT); output = VirtualValueKind.REFERENCE }
      is BridgeTarget.CheckCast -> { inputs.add(VirtualValueKind.REFERENCE); output = VirtualValueKind.REFERENCE }
      is BridgeTarget.InstanceOf -> { inputs.add(VirtualValueKind.REFERENCE); output = VirtualValueKind.INT }
      is BridgeTarget.StringConstant -> { output = VirtualValueKind.REFERENCE }
    }
    var id: Int
    do { id = random.nextInt() } while (id == 0 || entries.values.any { it.id == id })
    BridgeEntry(id, caller, target, BridgeSignature(inputs, output))
  }

  fun generate(runtime: ClassNode, classes: Map<String, ClassNode>, classpath: List<Path> = emptyList(),
    reserved: List<Pair<ClassNode, MethodNode>> = emptyList()): List<Pair<ClassNode, MethodNode>> {
    val generated = mutableListOf<Pair<ClassNode, MethodNode>>()
    val external = mutableMapOf<String, ClassNode?>()
    fun lookup(name: String): ClassNode? = classes[name] ?: external.getOrPut(name) {
      val bytes = classpath.firstNotNullOfOrNull { path ->
        if (Files.isDirectory(path)) path.resolve("$name.class").takeIf(Files::isRegularFile)?.let(Files::readAllBytes)
        else JarFile(path.toFile()).use { jar -> jar.getJarEntry("$name.class")?.let { jar.getInputStream(it).use { stream -> stream.readBytes() } } }
      } ?: javaClass.classLoader.getResourceAsStream("$name.class")?.use { it.readBytes() }
      bytes?.let { ClassNode().apply { ClassReader(it).accept(this, ClassReader.SKIP_CODE or ClassReader.SKIP_DEBUG or ClassReader.SKIP_FRAMES) } }
    }
    val dispatch = runtime.methods.single { it.name == "bridge" }
    dispatch.instructions.clear()
    dispatch.tryCatchBlocks.clear()
    dispatch.localVariables?.clear()
    val sorted = targets.sortedBy { it.id }
    val labels = sorted.map { LabelNode() }
    val default = LabelNode()
    dispatch.instructions.add(VarInsnNode(Opcodes.ILOAD, 0))
    dispatch.instructions.add(LookupSwitchInsnNode(default, sorted.map { it.id }.toIntArray(), labels.toTypedArray()))
    for ((index, entry) in sorted.withIndex()) {
      var name: String
      do { name = "b" + java.lang.Long.toUnsignedString(random.nextLong(), 36) }
      while (entry.caller.methods.any { it.name == name } || (reserved + generated).any { it.first === entry.caller && it.second.name == name })
      val method = MethodNode(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC or Opcodes.ACC_SYNTHETIC,
        name, "([Ljava/lang/Object;)Ljava/lang/Object;", null, null)
      val code = method.instructions
      fun argument(slot: Int, type: Type) {
        code.add(VarInsnNode(Opcodes.ALOAD, 0)); code.add(LdcInsnNode(slot)); code.add(InsnNode(Opcodes.AALOAD))
        if (JvmToVirtualCompiler.kind(type) == VirtualValueKind.INT) {
          code.add(TypeInsnNode(Opcodes.CHECKCAST, "java/lang/Integer"))
          code.add(MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/Integer", "intValue", "()I", false))
        } else if (JvmToVirtualCompiler.kind(type) == VirtualValueKind.DOUBLE) {
          code.add(TypeInsnNode(Opcodes.CHECKCAST, "java/lang/Double"))
          code.add(MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/Double", "doubleValue", "()D", false))
        } else if (JvmToVirtualCompiler.kind(type) == VirtualValueKind.LONG) {
          code.add(TypeInsnNode(Opcodes.CHECKCAST, "java/lang/Long"))
          code.add(MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/Long", "longValue", "()J", false))
        } else if (JvmToVirtualCompiler.kind(type) == VirtualValueKind.FLOAT) {
          code.add(TypeInsnNode(Opcodes.CHECKCAST, "java/lang/Float"))
          code.add(MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/Float", "floatValue", "()F", false))
        } else code.add(TypeInsnNode(Opcodes.CHECKCAST, type.internalName))
      }
      fun receiver(owner: String, special: Boolean = false, protected: Boolean = false) =
        argument(0, Type.getObjectType(if (special || protected) entry.caller.name else owner))
      fun protectedMember(owner: String, name: String, desc: String, field: Boolean): Boolean {
        var node: ClassNode? = lookup(owner) ?: throw IllegalArgumentException(
          "Cannot resolve bridge owner $owner; add its dependency to classpath")
        val seen = mutableSetOf<String>()
        while (node != null && seen.add(node.name)) {
          val access = if (field) node.fields.firstOrNull { it.name == name && it.desc == desc }?.access
            else node.methods.firstOrNull { it.name == name && it.desc == desc }?.access
          if (access != null) return access and Opcodes.ACC_PROTECTED != 0 &&
            node.name.substringBeforeLast('/', "") != entry.caller.name.substringBeforeLast('/', "")
          node = node.superName?.let { parent -> lookup(parent) ?: throw IllegalArgumentException(
            "Cannot resolve bridge superclass $parent; add its dependency to classpath") }
        }
        return false
      }
      val result: Type = when (val target = entry.target) {
        is BridgeTarget.Field -> {
          val type = Type.getType(target.descriptor)
          var slot = 0
          if (target.opcode in setOf(Opcodes.GETFIELD, Opcodes.PUTFIELD)) {
            receiver(target.owner, protected = protectedMember(target.owner, target.name, target.descriptor, true)); slot++
          }
          val read = target.opcode in setOf(Opcodes.GETFIELD, Opcodes.GETSTATIC)
          if (!read) argument(slot, type)
          code.add(FieldInsnNode(target.opcode, target.owner, target.name, target.descriptor))
          if (read) type else Type.VOID_TYPE
        }
        is BridgeTarget.Invoke -> {
          var slot = 0
          if (target.opcode != Opcodes.INVOKESTATIC) {
            receiver(target.owner, target.opcode == Opcodes.INVOKESPECIAL,
              protectedMember(target.owner, target.name, target.descriptor, false)); slot++
          }
          Type.getArgumentTypes(target.descriptor).forEach { argument(slot++, it) }
          code.add(MethodInsnNode(target.opcode, target.owner, target.name, target.descriptor, target.isInterface))
          Type.getReturnType(target.descriptor)
        }
        is BridgeTarget.InvokeDynamic -> {
          Type.getArgumentTypes(target.descriptor).forEachIndexed { slot, type -> argument(slot, type) }
          code.add(InvokeDynamicInsnNode(target.name, target.descriptor, target.bootstrap, *target.bootstrapArguments.toTypedArray()))
          Type.getReturnType(target.descriptor)
        }
        is BridgeTarget.Constructor -> {
          code.add(TypeInsnNode(Opcodes.NEW, target.owner)); code.add(InsnNode(Opcodes.DUP))
          Type.getArgumentTypes(target.descriptor).forEachIndexed { slot, type -> argument(slot, type) }
          code.add(MethodInsnNode(Opcodes.INVOKESPECIAL, target.owner, "<init>", target.descriptor, false))
          Type.getObjectType(target.owner)
        }
        is BridgeTarget.NewArray -> {
          argument(0, Type.INT_TYPE)
          if (target.opcode == Opcodes.NEWARRAY) code.add(IntInsnNode(Opcodes.NEWARRAY, target.type.toInt()))
          else code.add(TypeInsnNode(Opcodes.ANEWARRAY, target.type))
          Type.getType(if (target.opcode == Opcodes.NEWARRAY) when (target.type.toInt()) {
            Opcodes.T_BOOLEAN -> "[Z"; Opcodes.T_CHAR -> "[C"; Opcodes.T_FLOAT -> "[F"; Opcodes.T_DOUBLE -> "[D"
            Opcodes.T_BYTE -> "[B"; Opcodes.T_SHORT -> "[S"; Opcodes.T_INT -> "[I"; else -> "[J"
          } else if (target.type.startsWith("[")) "[${target.type}" else "[L${target.type};")
        }
        is BridgeTarget.CheckCast -> {
          argument(0, Type.getType("Ljava/lang/Object;")); code.add(TypeInsnNode(Opcodes.CHECKCAST, target.type)); Type.getType("Ljava/lang/Object;")
        }
        is BridgeTarget.InstanceOf -> {
          argument(0, Type.getType("Ljava/lang/Object;")); code.add(TypeInsnNode(Opcodes.INSTANCEOF, target.type)); Type.INT_TYPE
        }
        is BridgeTarget.StringConstant -> {
          code.add(LdcInsnNode(target.value)); Type.getType("Ljava/lang/String;")
        }
      }
      when (JvmToVirtualCompiler.kind(result)) {
        VirtualValueKind.INT -> code.add(MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/Integer", "valueOf", "(I)Ljava/lang/Integer;", false))
        VirtualValueKind.DOUBLE -> code.add(MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/Double", "valueOf", "(D)Ljava/lang/Double;", false))
        VirtualValueKind.LONG -> code.add(MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/Long", "valueOf", "(J)Ljava/lang/Long;", false))
        VirtualValueKind.FLOAT -> code.add(MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/Float", "valueOf", "(F)Ljava/lang/Float;", false))
        null -> code.add(InsnNode(Opcodes.ACONST_NULL))
        else -> Unit
      }
      code.add(InsnNode(Opcodes.ARETURN))
      method.maxLocals = 1
      method.maxStack = entry.signature.inputs.sumOf { if (it in setOf(VirtualValueKind.DOUBLE, VirtualValueKind.LONG)) 2.toInt() else 1.toInt() } + 4
      generated.add(entry.caller to method)
      dispatch.instructions.add(labels[index])
      dispatch.instructions.add(FrameNode(Opcodes.F_SAME, 0, null, 0, null))
      dispatch.instructions.add(VarInsnNode(Opcodes.ALOAD, 1))
      dispatch.instructions.add(MethodInsnNode(Opcodes.INVOKESTATIC, entry.caller.name, name, method.desc,
        entry.caller.access and Opcodes.ACC_INTERFACE != 0))
      dispatch.instructions.add(InsnNode(Opcodes.ARETURN))
    }
    dispatch.instructions.add(default)
    dispatch.instructions.add(FrameNode(Opcodes.F_SAME, 0, null, 0, null))
    dispatch.instructions.add(TypeInsnNode(Opcodes.NEW, "java/lang/IllegalStateException"))
    dispatch.instructions.add(InsnNode(Opcodes.DUP))
    dispatch.instructions.add(MethodInsnNode(Opcodes.INVOKESPECIAL, "java/lang/IllegalStateException", "<init>", "()V", false))
    dispatch.instructions.add(InsnNode(Opcodes.ATHROW))
    dispatch.maxStack = 2
    dispatch.maxLocals = 2
    return generated
  }
}
