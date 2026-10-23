package com.peco2282.obfaryn.transformer.linkage

import org.objectweb.asm.Handle
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import org.objectweb.asm.tree.*

/** Bounded linkage-only entrypoints. Caller initialization remains an explicit runtime action. */
internal object PrelinkGenerator {
  fun generate(owner: ClassNode, sites: List<InvokeDynamicInsnNode>, bootstrap: Handle) {
    require(owner.methods.none { it.name.startsWith("\$obfaryn\$prelink") }) { "Reserved prelink method collision in ${owner.name}" }
    val inInterface = owner.access and Opcodes.ACC_INTERFACE != 0
    val access = Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC or Opcodes.ACC_SYNTHETIC
    val chunks = sites.chunked(96)
    for ((index, chunk) in chunks.withIndex()) {
      val method = MethodNode(access, "\$obfaryn\$prelink\$$index", "(II)I", null, arrayOf("java/lang/Throwable"))
      val done = LabelNode()
      with(method.instructions) {
        add(InsnNode(Opcodes.ICONST_0)); add(VarInsnNode(Opcodes.ISTORE, 2))
        add(MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/invoke/MethodHandles", "lookup",
          "()Ljava/lang/invoke/MethodHandles\$Lookup;", false))
        add(VarInsnNode(Opcodes.ASTORE, 3))
        for ((siteIndex, site) in chunk.withIndex()) {
          val next = LabelNode()
          add(LdcInsnNode(siteIndex)); add(VarInsnNode(Opcodes.ILOAD, 0)); add(JumpInsnNode(Opcodes.IF_ICMPLT, next))
          add(VarInsnNode(Opcodes.ILOAD, 2)); add(VarInsnNode(Opcodes.ILOAD, 1)); add(JumpInsnNode(Opcodes.IF_ICMPGE, done))
          add(VarInsnNode(Opcodes.ALOAD, 3)); add(LdcInsnNode(site.name)); add(LdcInsnNode(Type.getMethodType(site.desc)))
          for (argument in site.bsmArgs) add(LdcInsnNode(argument))
          add(MethodInsnNode(Opcodes.INVOKESTATIC, bootstrap.owner, bootstrap.name, bootstrap.desc, false))
          add(InsnNode(Opcodes.POP)); add(IincInsnNode(2, 1)); add(next)
        }
        add(done); add(VarInsnNode(Opcodes.ILOAD, 2)); add(InsnNode(Opcodes.IRETURN))
      }
      method.maxLocals = 4; method.maxStack = 8
      owner.methods.add(method)
    }
    val entry = MethodNode(access, "\$obfaryn\$prelink", "(II)I", null, arrayOf("java/lang/Throwable"))
    with(entry.instructions) {
      add(InsnNode(Opcodes.ICONST_0)); add(VarInsnNode(Opcodes.ISTORE, 2))
      val done = LabelNode()
      for ((index, chunk) in chunks.withIndex()) {
        val next = LabelNode()
        add(VarInsnNode(Opcodes.ILOAD, 0)); add(LdcInsnNode(index * 96 + chunk.size)); add(JumpInsnNode(Opcodes.IF_ICMPGE, next))
        add(InsnNode(Opcodes.ICONST_0)); add(VarInsnNode(Opcodes.ILOAD, 0)); add(LdcInsnNode(index * 96)); add(InsnNode(Opcodes.ISUB))
        add(MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/Math", "max", "(II)I", false))
        add(VarInsnNode(Opcodes.ILOAD, 1)); add(VarInsnNode(Opcodes.ILOAD, 2)); add(InsnNode(Opcodes.ISUB))
        add(MethodInsnNode(Opcodes.INVOKESTATIC, owner.name, "\$obfaryn\$prelink\$$index", "(II)I", inInterface))
        add(VarInsnNode(Opcodes.ILOAD, 2)); add(InsnNode(Opcodes.IADD)); add(VarInsnNode(Opcodes.ISTORE, 2))
        add(VarInsnNode(Opcodes.ILOAD, 2)); add(VarInsnNode(Opcodes.ILOAD, 1)); add(JumpInsnNode(Opcodes.IF_ICMPGE, done))
        add(next)
      }
      add(done); add(VarInsnNode(Opcodes.ILOAD, 2)); add(InsnNode(Opcodes.IRETURN))
    }
    entry.maxLocals = 3; entry.maxStack = 3
    owner.methods.add(entry)
  }

}
