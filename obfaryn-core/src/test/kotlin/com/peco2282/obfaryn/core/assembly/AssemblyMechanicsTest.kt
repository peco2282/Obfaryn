package com.peco2282.obfaryn.core.assembly

import com.peco2282.obfaryn.api.context.ResourceEntry
import com.peco2282.obfaryn.core.context.ObfuscationContext
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.ModuleVisitor
import org.objectweb.asm.Opcodes.*
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.ModuleExportNode
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarFile

class AssemblyMechanicsTest {
  private fun classNode(name: String, superName: String = "java/lang/Object"): ClassNode {
    val cw = ClassWriter(0)
    cw.visit(V1_8, ACC_PUBLIC, name, null, superName, null)
    cw.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null).apply {
      visitCode(); visitVarInsn(ALOAD, 0); visitMethodInsn(INVOKESPECIAL, superName, "<init>", "()V", false)
      visitInsn(RETURN); visitMaxs(1, 1); visitEnd()
    }
    cw.visitEnd()
    return ClassNode().also { ClassReader(cw.toByteArray()).accept(it, 0) }
  }

  private fun moduleInfo(moduleName: String, vararg exports: String): ClassNode {
    val cw = ClassWriter(0)
    cw.visit(V9, ACC_MODULE, "module-info", null, null, null)
    val mv: ModuleVisitor = cw.visitModule(moduleName, 0, null)
    mv.visitRequire("java.base", ACC_MANDATED, null)
    for (export in exports) mv.visitExport(export, 0)
    mv.visitEnd()
    cw.visitEnd()
    return ClassNode().also { ClassReader(cw.toByteArray()).accept(it, 0) }
  }

  private fun context(vararg nodes: ClassNode) =
    ObfuscationContext(nodes.associateByTo(linkedMapOf()) { it.name }, linkedMapOf())

  @Test fun unionSharesNodesExcludesModuleInfoAndRecordsOrigins() {
    val foo = classNode("api/Foo")
    val bar = classNode("app/Bar")
    val apiModule = context(foo, moduleInfo("api", "api"))
    val appModule = context(bar)
    val union = ProgramUnion.build(listOf("api" to apiModule, "app" to appModule))

    assertEquals(setOf("api/Foo", "app/Bar"), union.context.classes.keys)
    assertFalse(ProgramUnion.MODULE_INFO in union.context.classes) { "module-info must not enter the union" }
    assertTrue(union.context.resources.isEmpty()) { "the union carries no resources" }
    assertSame(foo, union.context.classes["api/Foo"]) { "nodes are shared, not copied" }
    assertEquals(mapOf("api/Foo" to "api", "app/Bar" to "app"), union.origins)
  }

  @Test fun unionRejectsDuplicateClassAcrossArtifacts() {
    val a = context(classNode("shared/Helper"))
    val b = context(classNode("shared/Helper"))
    val failure = assertThrows(IllegalArgumentException::class.java) {
      ProgramUnion.build(listOf("a" to a, "b" to b))
    }
    assertTrue(failure.message!!.contains("shared/Helper"))
  }

  @Test fun pathValidationRejectsDuplicateOutputsAndOverwrittenInputs(@TempDir dir: Path) {
    val input = dir.resolve("in.jar")
    val out1 = dir.resolve("out1.jar")
    val out2 = dir.resolve("out2.jar")
    // Distinct destinations off every input: accepted.
    AssemblyPaths.validate(listOf("a" to out1, "b" to out2), listOf(input))
    // Two artifacts writing the same file: rejected.
    assertThrows(IllegalArgumentException::class.java) {
      AssemblyPaths.validate(listOf("a" to out1, "b" to out1), listOf(input))
    }
    // An output over an input: rejected.
    assertThrows(IllegalArgumentException::class.java) {
      AssemblyPaths.validate(listOf("a" to input), listOf(input))
    }
  }

  @Test fun pathValidationFollowsSymbolicLinksToTheSameFile(@TempDir dir: Path) {
    val input = Files.write(dir.resolve("real-in.jar"), "x".toByteArray())
    val link = Files.createSymbolicLink(dir.resolve("link-in.jar"), input)
    assertThrows(IllegalArgumentException::class.java) {
      // The output is a symlink that resolves to the protected input.
      AssemblyPaths.validate(listOf("a" to link), listOf(input))
    }
  }

  @Test fun splitterRoutesRenamedClassesByOriginAndRemapsModuleInfo(@TempDir dir: Path) {
    // A union as it looks after renaming: classes keyed by new name, originalClassNames mapping back.
    val renamedFoo = classNode("shaded/a")
    val renamedBar = classNode("shaded/b")
    val union = ObfuscationContext(
      linkedMapOf("shaded/a" to renamedFoo, "shaded/b" to renamedBar), linkedMapOf())
    union.originalClassNames["shaded/a"] = "api/Foo"
    union.originalClassNames["shaded/b"] = "app/Bar"
    val origins = mapOf("api/Foo" to "api", "app/Bar" to "app")
    val classMapping = mapOf("api/Foo" to "shaded/a", "app/Bar" to "shaded/b")
    val packageMapping = mapOf("api" to "shaded")
    val splitter = ArtifactSplitter(union, origins, classMapping, packageMapping)

    assertEquals(setOf("shaded/a"), splitter.classesFor("api").keys)
    assertEquals(setOf("shaded/b"), splitter.classesFor("app").keys)

    val apiOut = dir.resolve("api.jar")
    splitter.write("api", mapOf("api.txt" to ResourceEntry("api.txt", "hi".toByteArray())),
      moduleInfo("api.module", "api"), apiOut, reproducible = true, verifyOutput = true,
      recomputeFrames = false, classpath = emptyList())

    JarFile(apiOut.toFile()).use { jar ->
      assertNotNull(jar.getJarEntry("shaded/a.class")) { "api's renamed class is written" }
      assertNull(jar.getJarEntry("shaded/b.class")) { "app's class is not written into api's JAR" }
      assertNotNull(jar.getJarEntry("api.txt")) { "api keeps its own resources" }
      val moduleInfo = ClassNode().also {
        ClassReader(jar.getInputStream(jar.getJarEntry("module-info.class")).readBytes()).accept(it, 0)
      }
      val exported = moduleInfo.module.exports.map { (it as ModuleExportNode).packaze }
      assertEquals(listOf("shaded"), exported) { "module-info exports follow the global package mapping" }
      assertEquals("api.module", moduleInfo.module.name) { "the module's own name is never rewritten" }
    }
  }
}
