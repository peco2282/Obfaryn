package com.peco2282.obfaryn.core

import com.peco2282.obfaryn.core.context.ObfuscationContext
import com.peco2282.obfaryn.core.keep.KeepRules
import com.peco2282.obfaryn.core.rename.MemberKey
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
import kotlin.reflect.jvm.isAccessible
import kotlin.metadata.jvm.KotlinModuleMetadata
import org.jetbrains.kotlin.cli.common.ExitCode
import org.jetbrains.kotlin.cli.jvm.K2JVMCompiler

class KotlinRelocationTest {
  @TempDir lateinit var dir: Path
  private fun fixture(name: String): ClassNode = ClassNode().also { node ->
    javaClass.classLoader.getResourceAsStream("$name.class")!!.use { ClassReader(it).accept(node, 0) }
  }

  @Test fun privateDefaultArgumentsRenameWithTheirHelpersAndSupportReflection() {
    val node = fixture("fixture/defaults/PrivateDefaults")
    val original = node.name
    val context = ObfuscationContext(linkedMapOf(original to node), linkedMapOf())
    val rename = RenameTransformer(ObfuscationConfig(), KeepRules())
    rename.transform(context)
    val bytes = context.classes.mapValues { (_, value) -> ClassWriter(0).also { value.accept(it) }.toByteArray() }
    val loader = object : ClassLoader(javaClass.classLoader) {
      override fun findClass(name: String): Class<*> {
        val data = bytes[name.replace('.', '/')] ?: return super.findClass(name)
        return defineClass(name, data, 0, data.size)
      }
    }
    val type = loader.loadClass(rename.classMapping.getValue(original).replace('/', '.'))
    val instance = type.getConstructor().newInstance()
    val privateName = rename.methodMapping.getValue(MemberKey(original, "privateDefault", "(I)I"))
    assertEquals(privateName + "\$default", rename.methodMapping.getValue(MemberKey(
      original, "privateDefault\$default", "(L$original;IILjava/lang/Object;)I")))
    val function = type.kotlin.members.single { it.name == privateName } as kotlin.reflect.KFunction<*>
    function.isAccessible = true
    assertEquals(5, function.callBy(mapOf(function.parameters.first() to instance)))
    val caller = rename.methodMapping.getValue(MemberKey(original, "usePrivateDefault", "()I"))
    assertEquals(5, type.getMethod(caller).invoke(instance))
  }

  @Test fun kotlinClassesMoveWithMatchingMetadataAndRenamedMembers() {
    val nodes = listOf("fixture/KotlinData", "fixture/KotlinObject", "fixture/KotlinSample").map(::fixture)
    val context = ObfuscationContext(nodes.associateByTo(linkedMapOf()) { it.name }, linkedMapOf())
    val rename = RenameTransformer(ObfuscationConfig(
      flattenPackages = true, targetPackage = "flat", renameClasses = true,
      renameMethods = true, renameFields = true,
    ), KeepRules())
    rename.transform(context)
    assertEquals(nodes.size, rename.classMapping.size)
    assertTrue(context.classes.keys.all { it.substringBeforeLast('/') == "flat" })
    assertTrue(rename.methodMapping.isNotEmpty())
    assertTrue(rename.fieldMapping.isNotEmpty())
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
    assertEquals("Hello World!7Long", type.getMethod(rename.methodMapping.getValue(MemberKey("fixture/KotlinSample", "run", "(Ljava/lang/String;)Ljava/lang/String;")), String::class.java).invoke(type.getConstructor().newInstance(), "Hello"))
  }

  @OptIn(kotlin.metadata.jvm.UnstableMetadataApi::class)
  @Test fun enumAndPropertyMetadataFollowJvmRenamesAndSupportKotlinConsumers() {
    val names = listOf("ScoreLimit", "KotlinScore", "KotlinScore\$Companion", "KotlinCounter")
    val nodes = names.map { fixture("fixture/relocation/$it") }
    val keep = KeepRules().apply { keepMethod("fixture/relocation/KotlinCounter", "run", "()Ljava/lang/String;") }
    val context = ObfuscationContext(nodes.associateByTo(linkedMapOf()) { it.name }, linkedMapOf())
    val rename = RenameTransformer(ObfuscationConfig(
      flattenPackages = true, targetPackage = "flat", renameClasses = true,
      renameMethods = true, renameFields = true,
    ), keep)
    rename.transform(context)
    assertTrue(context.classes.keys.all { it.startsWith("flat/") })
    val bytes = context.classes.mapValues { (_, node) -> ClassWriter(0).also { node.accept(it) }.toByteArray() }
    val loader = object : ClassLoader(javaClass.classLoader) {
      override fun findClass(name: String): Class<*> {
        val data = bytes[name.replace('.', '/')] ?: return super.findClass(name)
        return defineClass(name, data, 0, data.size)
      }
    }
    fun mapped(name: String) = loader.loadClass(rename.classMapping.getValue("fixture/relocation/$name").replace('/', '.'))
    val counter = mapped("KotlinCounter")
    val instance = counter.getConstructor(Int::class.javaPrimitiveType).newInstance(1)
    assertEquals("ballball", counter.getMethod("run").invoke(instance))
    val score = mapped("KotlinScore")
    val metadata = kotlin.metadata.jvm.KotlinClassMetadata.readStrict(score.getAnnotation(Metadata::class.java))
      as kotlin.metadata.jvm.KotlinClassMetadata.Class
    val maximum = rename.methodMapping.getValue(MemberKey(
      "fixture/relocation/KotlinScore", "getMaximum", "()I"))
    assertTrue(metadata.kmClass.properties.any { it.name == maximum })
    assertFalse(metadata.kmClass.properties.any { it.name in setOf("maximum", "label") })
    assertFalse(metadata.kmClass.functions.any { it.name == "lamps" })
    assertEquals(4, score.kotlin.memberProperties.single { it.name == maximum }.getter.call(score.enumConstants.first()))
    assertNotNull(score.kotlin.companionObjectInstance)
    val limit = mapped("ScoreLimit")
    assertEquals(maximum, limit.kotlin.memberProperties.single().name)
    assertEquals(4, limit.kotlin.memberProperties.single().getter.call(score.enumConstants.first()))
    val count = rename.methodMapping.getValue(MemberKey(
      "fixture/relocation/KotlinCounter", "getCount", "()I"))
    val property = counter.kotlin.memberProperties.single { it.name == count }
    assertEquals(2, property.getter.call(instance))
    val mutable = property as kotlin.reflect.KMutableProperty<*>
    mutable.setter.call(instance, 3)
    assertEquals(3, mutable.getter.call(instance))
    val defaultAddName = rename.methodMapping.getValue(MemberKey(
      "fixture/relocation/KotlinCounter", "defaultAdd", "(I)I"))
    val defaultAdd = counter.kotlin.members.single { it.name == defaultAddName } as kotlin.reflect.KFunction<*>
    assertEquals(4, defaultAdd.callBy(mapOf(defaultAdd.parameters.first() to instance)))
    val library = dir.resolve("members.jar")
    JarOutputStream(Files.newOutputStream(library)).use { jar ->
      for ((name, data) in bytes) {
        jar.putNextEntry(JarEntry("$name.class"))
        jar.write(data)
        jar.closeEntry()
      }
    }
    val scoreName = score.name
    val counterName = counter.name
    val lamps = rename.methodMapping.getValue(MemberKey(
      "fixture/relocation/KotlinScore", "lamps", "(I)Ljava/lang/String;"))
    val consumer = dir.resolve("MembersConsumer.kt")
    Files.write(consumer, """
      fun read(value: $scoreName): Int = value.$maximum
      fun readInterface(value: ${limit.name}): Int = value.$maximum
      fun lamps(value: $scoreName): String = value.$lamps(1)
      fun update(value: $counterName): Int {
        value.$count = 5
        return value.$defaultAddName()
      }
    """.trimIndent().toByteArray())
    val stdlib = File(Unit::class.java.protectionDomain.codeSource.location.toURI())
    val diagnostics = ByteArrayOutputStream()
    val exit = PrintStream(diagnostics).use {
      K2JVMCompiler().exec(it, "-no-stdlib", "-no-reflect", "-classpath", "$library${File.pathSeparator}$stdlib",
        "-d", dir.resolve("members-consumer").toString(), consumer.toString())
    }
    assertEquals(ExitCode.OK, exit, diagnostics.toString())
  }

  @Test fun companionMetadataUsesRenamedFieldsStoredOnOuterClass() {
    val owner = "fixture/relocation/KotlinCompanionFields"
    val nodes = listOf(owner, "$owner\$Companion").map(::fixture)
    val context = ObfuscationContext(nodes.associateByTo(linkedMapOf()) { it.name }, linkedMapOf())
    val rename = RenameTransformer(ObfuscationConfig(), KeepRules())
    rename.transform(context)
    val node = context.classes.getValue(rename.classMapping.getValue("$owner\$Companion"))
    val annotation = node.visibleAnnotations.single { it.desc == "Lkotlin/Metadata;" }
    val strings = annotation.values[annotation.values.indexOf("d2") + 1] as List<*>
    assertFalse("SECRET_VALUE" in strings)
    assertFalse("payload" in strings)
    for ((name, desc) in listOf("SECRET_VALUE" to "I", "payload" to "Ljava/lang/String;")) {
      val mapped = rename.fieldMapping.getValue(MemberKey(owner, name, desc))
      assertTrue(mapped in strings)
      assertTrue(context.classes.getValue(rename.classMapping.getValue(owner)).fields.any { it.name == mapped })
    }
  }

  @Test fun callableReferencesKeepWorkingAfterRelocationAndMemberRenaming() {
    val names = listOf("KotlinReferenced", "KotlinReferenced\$propertyReference\$1", "KotlinReferenced\$functionReference\$1")
    val nodes = names.map { fixture("fixture/relocation/$it") }
    val context = ObfuscationContext(nodes.associateByTo(linkedMapOf()) { it.name }, linkedMapOf())
    val rename = RenameTransformer(ObfuscationConfig(
      flattenPackages = true, targetPackage = "flat", renameClasses = true,
      renameMethods = true, renameFields = true,
    ), KeepRules())
    rename.transform(context)
    val bytes = context.classes.mapValues { (_, node) -> ClassWriter(0).also { node.accept(it) }.toByteArray() }
    val loader = object : ClassLoader(javaClass.classLoader) {
      override fun findClass(name: String): Class<*> {
        val data = bytes[name.replace('.', '/')] ?: return super.findClass(name)
        return defineClass(name, data, 0, data.size)
      }
    }
    val owner = "fixture/relocation/KotlinReferenced"
    val type = loader.loadClass(rename.classMapping.getValue(owner).replace('/', '.'))
    val instance = type.getConstructor(Int::class.javaPrimitiveType).newInstance(7)
    val propertyMethod = rename.methodMapping.getValue(MemberKey(owner, "propertyReference", "()Lkotlin/reflect/KMutableProperty0;"))
    val reference = type.getMethod(propertyMethod).invoke(instance) as kotlin.reflect.KMutableProperty0<*>
    assertEquals(7, reference.get())
    reference.setter.call(9)
    assertEquals(9, reference.getter.call())
    val functionMethod = rename.methodMapping.getValue(MemberKey(owner, "functionReference", "()Lkotlin/reflect/KFunction;"))
    val function = type.getMethod(functionMethod).invoke(instance) as kotlin.reflect.KFunction<*>
    assertEquals(9, function.call())
  }

  @Test fun reflectionResolvesRelocatedNestedGenericSealedAndMultifileTypes() {
    val names = listOf("Model", "Model\$Nested", "Model\$Nested\$Deep", "Model\$Inner", "Model\$Snapshot",
      "Model\$Companion", "NamedCompanion", "NamedCompanion\$Child", "NamedCompanion\$a",
      "Leaf", "Outcome", "Outcome\$Success",
      "ModelKt", "Operations", "Operations__FirstKt", "Operations__SecondKt")
      .map { "fixture/relocation/$it" }
    val nodes = names.map(::fixture)
    val modulePath = "META-INF/com.peco2282.obfaryn_obfaryn-standard_test.kotlin_module"
    val moduleBytes = javaClass.classLoader.getResourceAsStream(modulePath)!!.use { it.readBytes() }
    val context = ObfuscationContext(nodes.associateByTo(linkedMapOf()) { it.name },
      linkedMapOf(modulePath to ResourceEntry(modulePath, moduleBytes)))
    val rename = RenameTransformer(ObfuscationConfig(
      flattenPackages = true, targetPackage = "flat", renameClasses = true,
      renameMethods = false, renameFields = false,
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
    for (name in listOf("Model\$Nested", "Model\$Nested\$Deep", "Model\$Inner", "Model\$Snapshot", "Outcome\$Success", "NamedCompanion\$Child")) {
      val original = "fixture/relocation/$name"
      val target = rename.classMapping.getValue(original)
      val oldOuter = original.substringBeforeLast('$')
      assertEquals(rename.classMapping.getValue(oldOuter), target.substringBeforeLast('$'))
      assertNotEquals(original.substringAfterLast('$'), target.substringAfterLast('$'))
      val entry = context.classes.getValue(target).innerClasses.single { it.name == target }
      assertEquals(target.substringAfterLast('$'), entry.innerName)
      assertEquals(target.substringBeforeLast('$'), entry.outerName)
    }
    val deep = mapped("Model\$Nested\$Deep")
    assertEquals(nested, deep.enclosingClass)
    assertTrue(nested.kotlin.nestedClasses.any { it.java == deep })
    assertEquals(model, mapped("Model\$Inner").enclosingClass)
    assertTrue(model.kotlin.nestedClasses.any { it.java == mapped("Model\$Snapshot") })
    assertEquals(model.kotlin, nested.kotlin.memberProperties.single().returnType.classifier)
    assertEquals(model.kotlin, model.kotlin.typeParameters.single().upperBounds.single().classifier)
    assertTrue(model.kotlin.nestedClasses.any { it.java == nested })
    assertNotNull(model.kotlin.companionObjectInstance)
    assertNotNull(mapped("NamedCompanion").kotlin.companionObjectInstance)
    assertEquals("a", mapped("NamedCompanion\$a").simpleName)
    assertEquals(mapped("Outcome\$Success").kotlin, mapped("Outcome").kotlin.sealedSubclasses.single())
    val value = leaf.getConstructor(String::class.java).newInstance("test")
    val inner = mapped("Model\$Inner")
    val innerInstance = inner.getConstructor(model, String::class.java).newInstance(value, "inner")
    assertEquals("inner", inner.getMethod("getText").invoke(innerInstance))
    val deepInstance = deep.getConstructor(model).newInstance(value)
    assertSame(value, deep.getMethod("getModel").invoke(deepInstance))
    val snapshot = mapped("Model\$Snapshot")
    val snapshotInstance = snapshot.getConstructor(String::class.java).newInstance("before")
    val copied = snapshot.getMethod("copy", String::class.java).invoke(snapshotInstance, "after")
    assertEquals("after", snapshot.getMethod("getText").invoke(copied))
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
      fun nested(value: ${model.name}.${nested.simpleName}): ${model.name}<*> = value.model
      fun deep(value: ${model.name}.${nested.simpleName}.${deep.simpleName}): ${model.name}<*> = value.model
      fun snapshot(): ${model.name}.${mapped("Model\$Snapshot").simpleName} =
        ${model.name}.${mapped("Model\$Snapshot").simpleName}("ok")
    """.trimIndent().toByteArray())
    val stdlib = File(Unit::class.java.protectionDomain.codeSource.location.toURI())
    val diagnostics = ByteArrayOutputStream()
    val exit = PrintStream(diagnostics).use {
      K2JVMCompiler().exec(it, "-no-stdlib", "-no-reflect", "-classpath", "$library${File.pathSeparator}$stdlib",
        "-d", dir.resolve("consumer").toString(), consumer.toString())
    }
    assertEquals(ExitCode.OK, exit, diagnostics.toString())
  }

  @Test fun keptNestedClassRetainsItsEnclosingNamesWhileItsChildrenCanRename() {
    val nodes = listOf("Model", "Model\$Nested", "Model\$Nested\$Deep", "Model\$Companion")
      .map { fixture("fixture/relocation/$it") }
    val context = ObfuscationContext(nodes.associateByTo(linkedMapOf()) { it.name }, linkedMapOf())
    val keep = KeepRules().apply { keepClassNameOnly("fixture/relocation/Model\$Nested") }
    val rename = RenameTransformer(ObfuscationConfig(
      flattenPackages = true, targetPackage = "flat", renameClasses = true,
      renameMethods = false, renameFields = false,
    ), keep)
    rename.transform(context)
    assertFalse("fixture/relocation/Model" in rename.classMapping)
    assertFalse("fixture/relocation/Model\$Nested" in rename.classMapping)
    val deep = rename.classMapping.getValue("fixture/relocation/Model\$Nested\$Deep")
    assertTrue(deep.startsWith("fixture/relocation/Model\$Nested\$"))
    assertNotEquals("Deep", deep.substringAfterLast('$'))
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