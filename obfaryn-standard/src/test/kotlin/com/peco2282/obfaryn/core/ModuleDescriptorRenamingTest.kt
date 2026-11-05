package com.peco2282.obfaryn.core

import com.peco2282.obfaryn.core.context.ObfuscationContext
import com.peco2282.obfaryn.core.keep.KeepRules
import com.peco2282.obfaryn.core.rename.RenameTransformer
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.ModuleVisitor
import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.ClassNode

/**
 * `module-info.class` flows through the same [org.objectweb.asm.commons.ClassRemapper] pass as
 * ordinary classes (ASM's [org.objectweb.asm.commons.ModuleRemapper] already remaps `uses`/
 * `provides`/main-class via [org.objectweb.asm.commons.Remapper.map]); the only piece
 * [RenameTransformer] must supply itself is `exports`/`opens` package rewriting under
 * `--flatten-packages`, via [com.peco2282.obfaryn.api.transformer.RenamingTransformer.packageMapping].
 */
class ModuleDescriptorRenamingTest {
  private fun simpleClass(name: String, interfaces: Array<String> = emptyArray()): ClassNode {
    val writer = ClassWriter(0)
    writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, name, null, "java/lang/Object", interfaces)
    writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null).apply {
      visitCode(); visitVarInsn(Opcodes.ALOAD, 0)
      visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false)
      visitInsn(Opcodes.RETURN); visitMaxs(1, 1); visitEnd()
    }
    writer.visitEnd()
    val node = ClassNode()
    ClassReader(writer.toByteArray()).accept(node, 0)
    return node
  }

  private fun moduleInfo(moduleName: String, build: ModuleVisitor.() -> Unit): ClassNode {
    val writer = ClassWriter(0)
    writer.visit(Opcodes.V17, Opcodes.ACC_MODULE, "module-info", null, null, null)
    val module = writer.visitModule(moduleName, 0, null)
    module.build()
    module.visitEnd()
    writer.visitEnd()
    val node = ClassNode()
    ClassReader(writer.toByteArray()).accept(node, 0)
    return node
  }

  @Test fun `exports, opens, uses, provides and main-class follow a fully relocated package`() {
    val greeter = simpleClass("fixture/app/Greeter")
    val module = moduleInfo("test.module") {
      visitExport("fixture/app", 0)
      visitOpen("fixture/app", 0)
      visitUse("fixture/app/Greeter")
      visitProvide("fixture/app/Greeter", "fixture/app/Greeter")
      visitMainClass("fixture/app/Greeter")
    }
    val context = ObfuscationContext(
      linkedMapOf(greeter.name to greeter, module.name to module), linkedMapOf())
    val rename = RenameTransformer(ObfuscationConfig(
      flattenPackages = true, targetPackage = "flat", renameClasses = true,
    ), KeepRules())
    rename.transform(context)

    assertEquals(mapOf("fixture/app" to "flat"), rename.packageMapping)
    assertTrue(rename.warnings.none { "split" in it }, rename.warnings.toString())
    val renamedGreeter = rename.classMapping.getValue("fixture/app/Greeter")
    assertEquals("flat", renamedGreeter.substringBeforeLast('/'))

    val rewritten = context.classes.getValue("module-info").module
    assertEquals("test.module", rewritten.name)
    assertEquals(listOf("flat"), rewritten.exports.map { it.packaze })
    assertEquals(listOf("flat"), rewritten.opens.map { it.packaze })
    assertEquals(listOf(renamedGreeter), rewritten.uses)
    assertEquals(listOf(renamedGreeter), rewritten.provides.map { it.service })
    assertEquals(listOf(listOf(renamedGreeter)), rewritten.provides.map { it.providers })
    assertEquals(renamedGreeter, rewritten.mainClass)
  }

  @Test fun `exports stay unchanged when a protected sibling pins the whole package in place`() {
    // A Serializable class is a protected structure the renamer never moves; the existing
    // pinnedPackages mechanism keeps its *whole* original package together rather than splitting
    // it, so Widget stays alongside Legacy and the export directive is correctly left untouched.
    val widget = simpleClass("fixture/mixed/Widget")
    val legacy = simpleClass("fixture/mixed/Legacy", arrayOf("java/io/Serializable"))
    val module = moduleInfo("test.module") { visitExport("fixture/mixed", 0) }
    val context = ObfuscationContext(
      linkedMapOf(widget.name to widget, legacy.name to legacy, module.name to module), linkedMapOf())
    val rename = RenameTransformer(ObfuscationConfig(
      flattenPackages = true, targetPackage = "flat", renameClasses = true,
    ), KeepRules())
    rename.transform(context)

    assertTrue("fixture/mixed" !in rename.packageMapping, rename.packageMapping.toString())
    assertTrue(rename.warnings.any { "fixture/mixed" in it && "preserved" in it }, rename.warnings.toString())
    // Widget is still renamed in place (pinning keeps the *package*, not every class's own name).
    assertEquals("fixture/mixed", rename.classMapping.getValue("fixture/mixed/Widget").substringBeforeLast('/'))
    assertFalse("fixture/mixed/Legacy" in rename.classMapping)

    val rewritten = context.classes.getValue("module-info").module
    assertEquals(listOf("fixture/mixed"), rewritten.exports.map { it.packaze })
  }

  @Test fun `requires and the module's own name are never rewritten`() {
    val widget = simpleClass("fixture/app/Widget")
    val module = moduleInfo("test.module") {
      visitRequire("some.other.module", 0, null)
      visitExport("fixture/app", 0)
    }
    val context = ObfuscationContext(
      linkedMapOf(widget.name to widget, module.name to module), linkedMapOf())
    val rename = RenameTransformer(ObfuscationConfig(
      flattenPackages = true, targetPackage = "flat", renameClasses = true,
    ), KeepRules())
    rename.transform(context)

    val rewritten = context.classes.getValue("module-info").module
    assertEquals("test.module", rewritten.name)
    assertEquals(listOf("some.other.module"), rewritten.requires.map { it.module })
    assertEquals(listOf("flat"), rewritten.exports.map { it.packaze })
  }
}
