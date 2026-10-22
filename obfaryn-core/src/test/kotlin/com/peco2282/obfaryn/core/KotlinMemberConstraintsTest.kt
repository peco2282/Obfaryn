package com.peco2282.obfaryn.core

import com.peco2282.obfaryn.core.context.ObfuscationContext
import com.peco2282.obfaryn.core.keep.KeepRules
import com.peco2282.obfaryn.core.rename.KotlinMemberConstraints
import com.peco2282.obfaryn.core.rename.MemberKey
import com.peco2282.obfaryn.core.rename.RenameTransformer
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.*

class KotlinMemberConstraintsTest {
  private fun node(name: String) = ClassNode().apply {
    version = Opcodes.V1_8
    access = Opcodes.ACC_PUBLIC
    this.name = name
    superName = "java/lang/Object"
  }

  @Test fun compilerHelpersDoNotKeepUnrelatedNamesOrInterfaceDeclarations() {
    val helper = node("sample/Mode\$DefaultImpls").apply {
      invisibleAnnotations = mutableListOf(AnnotationNode("Lkotlin/Metadata;").apply {
        values = mutableListOf("k", 3)
      })
      for (name in listOf("onStart", "onLoad", "getPlugin")) {
        methods.add(MethodNode(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, name, "()V", null, null))
      }
    }
    val contract = node("sample/Mode").apply {
      access = Opcodes.ACC_PUBLIC or Opcodes.ACC_INTERFACE or Opcodes.ACC_ABSTRACT
      methods.add(MethodNode(Opcodes.ACC_PUBLIC or Opcodes.ACC_ABSTRACT, "onStart", "()V", null, null))
    }
    val impl = node("sample/Impl").apply {
      interfaces.add(contract.name)
      methods.add(MethodNode(Opcodes.ACC_PUBLIC, "onStart", "()V", null, null).apply {
        instructions.add(InsnNode(Opcodes.RETURN))
      })
    }
    val keep = KeepRules()
    KotlinMemberConstraints.protect(listOf(helper, contract, impl), keep)
    assertTrue(keep.method(helper.name, "onStart", "()V"))
    assertFalse(keep.method(contract.name, "onStart", "()V"))
    assertFalse(keep.method(impl.name, "onStart", "()V"))
    assertFalse(keep.method("unrelated/Type", "getPlugin", "()V"))
    val context = ObfuscationContext(listOf(contract, impl).associateByTo(linkedMapOf()) { it.name }, linkedMapOf())
    val rename = RenameTransformer(ObfuscationConfig(), keep)
    rename.transform(context)
    val renamed = rename.methodMapping[MemberKey("sample/Mode", "onStart", "()V")]
    assertNotNull(renamed)
    assertEquals(renamed, rename.methodMapping[MemberKey("sample/Impl", "onStart", "()V")])
  }

  @Test fun reflectionKeepsOnlyTheResolvedOwnerAndDoesNotGuessUnknownOwners() {
    val target = node("sample/Target").apply {
      methods.add(MethodNode(Opcodes.ACC_PUBLIC, "onLoad", "()V", null, null))
    }
    val caller = node("sample/Caller").apply {
      methods.add(MethodNode(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "inspect", "()V", null, null).apply {
        instructions.add(LdcInsnNode(org.objectweb.asm.Type.getObjectType(target.name)))
        instructions.add(LdcInsnNode("onLoad"))
        instructions.add(InsnNode(Opcodes.ICONST_0))
        instructions.add(TypeInsnNode(Opcodes.ANEWARRAY, "java/lang/Class"))
        instructions.add(MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/Class", "getDeclaredMethod", "(Ljava/lang/String;[Ljava/lang/Class;)Ljava/lang/reflect/Method;", false))
        instructions.add(InsnNode(Opcodes.POP))
        instructions.add(InsnNode(Opcodes.RETURN))
        maxStack = 3
      })
    }
    val context = ObfuscationContext(listOf(target, caller).associateByTo(linkedMapOf()) { it.name }, linkedMapOf())
    val keep = KeepRules()
    ReflectionScanner.scan(context, keep)
    assertTrue(keep.method(target.name, "onLoad", "()V"))
    assertFalse(keep.method("other/Type", "onLoad", "()V"))
    caller.methods.single().instructions.set(caller.methods.single().instructions.first, InsnNode(Opcodes.ACONST_NULL))
    val unknown = KeepRules()
    assertTrue(ReflectionScanner.scan(context, unknown).any { "Unresolved reflective member target" in it })
    assertFalse(unknown.method(target.name, "onLoad", "()V"))
  }
}
