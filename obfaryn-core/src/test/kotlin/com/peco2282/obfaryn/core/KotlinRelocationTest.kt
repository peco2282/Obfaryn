package com.peco2282.obfaryn.core

import com.peco2282.obfaryn.core.context.ObfuscationContext
import com.peco2282.obfaryn.core.keep.KeepRules
import com.peco2282.obfaryn.core.rename.RenameTransformer
import com.peco2282.obfaryn.api.context.ResourceEntry
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.tree.ClassNode
import java.nio.file.Files
import java.nio.file.Path
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import java.util.jar.JarEntry
import java.util.jar.JarFile
import java.util.jar.JarOutputStream
import kotlin.reflect.full.companionObjectInstance
import kotlin.reflect.full.memberProperties
import kotlin.metadata.jvm.KotlinModuleMetadata
import org.jetbrains.kotlin.cli.common.ExitCode
import org.jetbrains.kotlin.cli.jvm.K2JVMCompiler

class KotlinRelocationTest {
  @TempDir lateinit var dir: Path
  private fun fixture(name: String): ClassNode = ClassNode().also { node ->
    javaClass.classLoader.getResourceAsStream("$name.class")!!.use { ClassReader(it).accept(node, 0) }
  }

  @Test fun kotlinClassesMoveWithoutLosingMetadataOrMemberNames() {
    val nodes = listOf("fixture/KotlinData", "fixture/KotlinObject", "fixture/KotlinSample").map(::fixture)
    val context = ObfuscationContext(nodes.associateByTo(linkedMapOf()) { it.name }, linkedMapOf())
    val rename = RenameTransformer(ObfuscationConfig(
      flattenPackages = true, targetPackage = "flat", renameClasses = true,
      renameMethods = true, renameFields = true,
    ), KeepRules())
    rename.transform(context)
    assertEquals(nodes.size, rename.classMapping.size)
    assertTrue(context.classes.keys.all { it.substringBeforeLast('/') == "flat" })
    assertTrue(rename.methodMapping.isEmpty())
    assertTrue(rename.fieldMapping.isEmpty())
    val bytes = context.classes.mapValues { (_, node) ->
      ClassWriter(0).also { node.accept(it) }.toByteArray()
    }
    val loader = object : ClassLoader(javaClass.classLoader) {
      override fun findClass(name: String): Class<*> {
        val data = bytes[name.replace('.', '/')] ?: return super.findClass(name)
        return defineClass(name, data, 0, data.size)
      }
    }
    val type = loader.loadClass(rename.classMapping.getValue("fixture/KotlinSample").replace('/', '.'))
    assertNotNull(type.getAnnotation(Metadata::class.java))
    assertEquals("Hello World!7Long", type.getMethod("run", String::class.java).invoke(type.getConstructor().newInstance(), "Hello"))
  }

  @Test fun reflectionResolvesRelocatedNestedGenericSealedAndMultifileTypes() {
    val names = listOf("Model", "Model\$Nested", "Model\$Companion", "Leaf", "Outcome", "Outcome\$Success",
      "ModelKt", "Operations", "Operations__FirstKt", "Operations__SecondKt")
      .map { "fixture/relocation/$it" }
    val nodes = names.map(::fixture)
    val modulePath = "META-INF/com.peco2282.obfaryn_obfaryn-core_test.kotlin_module"
    val moduleBytes = javaClass.classLoader.getResourceAsStream(modulePath)!!.use { it.readBytes() }
    val context = ObfuscationContext(nodes.associateByTo(linkedMapOf()) { it.name },
      linkedMapOf(modulePath to ResourceEntry(modulePath, moduleBytes)))
    val rename = RenameTransformer(ObfuscationConfig(
      flattenPackages = true, targetPackage = "flat", renameClasses = true,
    ), KeepRules())
    rename.transform(context)
    val module = KotlinModuleMetadata.read(context.resources.getValue(modulePath).data).kmModule
    val parts = module.packageParts.getValue("flat")
    assertTrue(rename.classMapping.getValue("fixture/relocation/ModelKt") in parts.fileFacades)
    assertEquals(rename.classMapping.getValue("fixture/relocation/Operations"),
      parts.multiFileClassParts[rename.classMapping.getValue("fixture/relocation/Operations__FirstKt")])
    val bytes = context.classes.mapValues { (_, node) -> ClassWriter(0).also { node.accept(it) }.toByteArray() }
    val loader = object : ClassLoader(javaClass.classLoader) {
      override fun findClass(name: String): Class<*> {
        val data = bytes[name.replace('.', '/')] ?: return super.findClass(name)
        return defineClass(name, data, 0, data.size)
      }
    }
    fun mapped(name: String) = loader.loadClass(rename.classMapping.getValue("fixture/relocation/$name").replace('/', '.'))
    val model = mapped("Model")
    val leaf = mapped("Leaf")
    val nested = mapped("Model\$Nested")
    assertEquals(model.kotlin, nested.kotlin.memberProperties.single().returnType.classifier)
    assertEquals(model.kotlin, model.kotlin.typeParameters.single().upperBounds.single().classifier)
    assertTrue(model.kotlin.nestedClasses.any { it.java == nested })
    assertNotNull(model.kotlin.companionObjectInstance)
    assertEquals(mapped("Outcome\$Success").kotlin, mapped("Outcome").kotlin.sealedSubclasses.single())
    val value = leaf.getConstructor(String::class.java).newInstance("test")
    for (function in listOf("first", "second")) {
      assertSame(value, mapped("Operations").getMethod(function, leaf).invoke(null, value))
    }
    val library = dir.resolve("library.jar")
    JarOutputStream(Files.newOutputStream(library)).use { jar ->
      for ((name, data) in bytes.mapKeys { "${it.key}.class" } + context.resources.mapValues { it.value.data }) {
        jar.putNextEntry(JarEntry(name))
        jar.write(data)
        jar.closeEntry()
      }
    }
    val consumer = dir.resolve("Consumer.kt")
    Files.write(consumer, """
      import flat.ModelAlias
      import flat.identity
      import flat.first
      import flat.second
      fun consume(value: ModelAlias): ModelAlias = second(first(identity(value)))
    """.trimIndent().toByteArray())
    val stdlib = File(Unit::class.java.protectionDomain.codeSource.location.toURI())
    val diagnostics = ByteArrayOutputStream()
    val exit = PrintStream(diagnostics).use {
      K2JVMCompiler().exec(it, "-no-stdlib", "-no-reflect", "-classpath", "$library${File.pathSeparator}$stdlib",
        "-d", dir.resolve("consumer").toString(), consumer.toString())
    }
    assertEquals(ExitCode.OK, exit, diagnostics.toString())
  }

  @Test fun autoPreservesApplicationMetadataInOutputJar() {
    val input = dir.resolve("input.jar")
    JarOutputStream(Files.newOutputStream(input)).use { jar ->
      for (name in listOf("fixture/KotlinData", "fixture/KotlinObject", "fixture/KotlinSample")) {
        jar.putNextEntry(JarEntry("$name.class"))
        javaClass.classLoader.getResourceAsStream("$name.class")!!.use { it.copyTo(jar) }
        jar.closeEntry()
      }
    }
    val output = dir.resolve("output.jar")
    val result = Obfuscator(ObfuscationConfig(
      applicationPackages = listOf("fixture"), flattenPackages = true, targetPackage = "flat",
      stringEncryption = false, numberObfuscation = false, verifyOutput = true,
    )).obfuscate(input, output)
    assertEquals(3, result.classesRenamed)
    assertFalse(result.warnings.any { "stripped kotlin.Metadata" in it })
    JarFile(output.toFile()).use { jar ->
      for (entry in jar.entries().asSequence().filter { it.name.endsWith(".class") }) {
        assertTrue(entry.name.startsWith("flat/"))
        val node = ClassNode()
        jar.getInputStream(entry).use { ClassReader(it).accept(node, 0) }
        assertTrue(node.visibleAnnotations.any { it.desc == "Lkotlin/Metadata;" })
      }
    }
  }
}