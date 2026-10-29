package com.peco2282.obfaryn.core

import com.peco2282.obfaryn.core.context.ObfuscationContext
import com.peco2282.obfaryn.core.keep.KeepRules
import com.peco2282.obfaryn.core.rename.MemberKey
import com.peco2282.obfaryn.core.rename.RenameTransformer
import org.jetbrains.kotlin.cli.common.ExitCode
import org.jetbrains.kotlin.cli.jvm.K2JVMCompiler
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.tree.ClassNode
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import java.util.stream.Collectors
import java.lang.reflect.Method
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import javax.tools.ToolProvider
import kotlin.reflect.full.memberProperties
import kotlin.reflect.full.memberFunctions

class RecordAndValueClassRenamingTest {
  @TempDir lateinit var dir: Path
  private val stdlib: File get() = File(Unit::class.java.protectionDomain.codeSource.location.toURI())

  private fun writeSource(path: Path, text: String) {
    Files.write(path, text.toByteArray())
  }

  private fun recordComponents(type: Class<*>): List<Any> =
    (Class::class.java.getMethod("getRecordComponents").invoke(type) as Array<*>).map { it!! }

  private fun component(component: Any, method: String): Any =
    component.javaClass.getMethod(method).invoke(component)

  private fun compile(source: Path, output: Path, library: Path? = null) {
    val diagnostics = ByteArrayOutputStream()
    val classpath = listOfNotNull(library?.toString(), stdlib.toString()).joinToString(File.pathSeparator)
    val exit = PrintStream(diagnostics).use {
      K2JVMCompiler().exec(it, "-no-stdlib", "-no-reflect", "-jvm-target", "17",
        "-classpath", classpath, "-d", output.toString(), source.toString())
    }
    assertEquals(ExitCode.OK, exit, diagnostics.toString())
  }

  private fun rename(input: Path, keep: KeepRules = KeepRules(), transformBodies: Boolean = false): Pair<RenameTransformer, Path> {
    val nodes = Files.walk(input).use { paths ->
      paths.filter { it.toString().endsWith(".class") }.sorted().map { path ->
        ClassNode().also { ClassReader(Files.readAllBytes(path)).accept(it, 0) }
      }.collect(Collectors.toList())
    }
    val context = ObfuscationContext(nodes.associateByTo(linkedMapOf()) { it.name }, linkedMapOf())
    val rename = RenameTransformer(ObfuscationConfig(
      flattenPackages = true, targetPackage = "hidden", applicationPackages = listOf("sample"),
    ), keep)
    rename.transform(context)
    val jar = dir.resolve("renamed.jar")
    JarOutputStream(Files.newOutputStream(jar)).use { out ->
      for (node in context.classes.values) {
        val writer = ClassWriter(0)
        node.accept(writer)
        out.putNextEntry(JarEntry(node.name + ".class"))
        out.write(writer.toByteArray())
        out.closeEntry()
      }
    }
    if (!transformBodies) return rename to jar
    val transformed = dir.resolve("transformed.jar")
    Obfuscator(ObfuscationConfig(
      renameClasses = false, renameMethods = false, renameFields = false,
      controlFlow = true, verifyOutput = true, classpath = listOf(stdlib.toPath()), seed = 43,
    )).obfuscate(jar, transformed)
    return rename to transformed
  }

  @Test fun javaRecordsRelocateWithComponentReflectionAndObjectMethods() {
    val source = dir.resolve("Entry.java")
    writeSource(source, """
      package sample;
      public record Entry(String label, long count) {}
    """.trimIndent())
    val input = dir.resolve("java")
    Files.createDirectories(input)
    assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null,
      "--release", "17", "-parameters", "-d", input.toString(), source.toString()))
    val (rename, jar) = rename(input)
    val name = rename.classMapping.getValue("sample/Entry").replace('/', '.')
    assertTrue(name.startsWith("hidden."))
    URLClassLoader(arrayOf(jar.toUri().toURL()), javaClass.classLoader).use { loader ->
      val type = loader.loadClass(name)
      assertEquals(true, Class::class.java.getMethod("isRecord").invoke(type))
      assertEquals(listOf("label", "count"), recordComponents(type).map { component(it, "getName") })
      assertEquals(listOf(String::class.java, Long::class.javaPrimitiveType), recordComponents(type).map { component(it, "getType") })
      val constructor = type.getConstructor(String::class.java, Long::class.javaPrimitiveType)
      assertEquals(listOf("label", "count"), constructor.parameters.map { it.name })
      val first = constructor.newInstance("team", 7L)
      val equal = constructor.newInstance("team", 7L)
      val different = constructor.newInstance("other", 8L)
      assertEquals(first, equal)
      assertNotEquals(first, different)
      assertEquals(first.hashCode(), equal.hashCode())
      assertEquals("team", (component(recordComponents(type)[0], "getAccessor") as Method).invoke(first))
      assertEquals(7L, (component(recordComponents(type)[1], "getAccessor") as Method).invoke(first))
      assertTrue(first.toString().contains("label=team"))
      assertTrue(first.toString().startsWith(type.simpleName + "["))
    }
    val (kept, _) = rename(input, KeepRules().apply { keepClassNameOnly("sample/Entry") })
    assertFalse("sample/Entry" in kept.classMapping)
  }

  @Test fun nestedKotlinRecordsNoLongerPinTheirOwnersAndRemainConsumable() {
    val source = dir.resolve("Records.kt")
    writeSource(source, """
      package sample
      class Holder {
        @JvmRecord data class Entry(val label: String, val count: Int)
        companion object { fun make(): Entry = Entry("team", 7) }
        fun run(): String = make().copy(count = 8).label
      }
    """.trimIndent())
    val input = dir.resolve("records")
    compile(source, input)
    val (rename, jar) = rename(input, transformBodies = true)
    val holder = rename.classMapping.getValue("sample/Holder").replace('/', '.')
    val entry = rename.classMapping.getValue("sample/Holder\$Entry").replace('/', '.').replace('$', '.')
    assertTrue(holder.startsWith("hidden."))
    assertTrue(entry.startsWith("$holder."))
    val consumer = dir.resolve("RecordConsumer.kt")
    writeSource(consumer, """
      fun run(): String {
        val value = $entry("team", 7)
        val (label, count) = value.copy(count = 8)
        return label + count + (value == $entry("team", 7))
      }
    """.trimIndent())
    val output = dir.resolve("record-consumer")
    compile(consumer, output, jar)
    URLClassLoader(arrayOf(output.toUri().toURL(), jar.toUri().toURL()), javaClass.classLoader).use { loader ->
      assertEquals("team8true", loader.loadClass("RecordConsumerKt").getMethod("run").invoke(null))
      val type = loader.loadClass(holder)
      val run = rename.methodMapping.getValue(MemberKey("sample/Holder", "run", "()Ljava/lang/String;"))
      assertEquals("team", type.getMethod(run).invoke(type.getConstructor().newInstance()))
      val record = loader.loadClass(rename.classMapping.getValue("sample/Holder" + "$" + "Entry").replace('/', '.'))
      assertEquals(true, Class::class.java.getMethod("isRecord").invoke(record))
      assertEquals(setOf("label", "count"), record.kotlin.memberProperties.map { it.name }.toSet())
    }
  }

  @Test fun valueClassesRelocateAndSupportBoxingReflectionAndNewKotlinConsumers() {
    val source = dir.resolve("Values.kt")
    writeSource(source, """
      package sample
      @JvmInline value class Ticket(val raw: Int) {
        fun add(delta: Int) = Ticket(raw + delta)
        val doubled: Int get() = raw * 2
        val next: Ticket get() = Ticket(raw + 1)
      }
      @JvmInline value class Label(val text: String) {
        fun decorate(suffix: String = "!"): String = text + suffix
      }
      class ValueApi {
        fun read(value: Ticket): Int = value.raw
        fun boxed(value: Ticket?): Ticket? = value
        fun describe(value: Label): String = value.decorate()
        @JvmName("explicitLabel") fun explicit(value: Label): String = value.text
        fun run(): Int {
          val boxed: Any = Ticket(7)
          return (boxed as Ticket).add(2).doubled
        }
      }
    """.trimIndent())
    val input = dir.resolve("values")
    compile(source, input)
    val (rename, jar) = rename(input, transformBodies = true)
    fun name(original: String) = rename.classMapping.getValue("sample/$original").replace('/', '.')
    val ticket = name("Ticket")
    val label = name("Label")
    val api = name("ValueApi")
    val read = rename.methodMapping.entries.single { it.key.owner == "sample/ValueApi" && it.key.name.startsWith("read-") }.value
    val boxed = rename.methodMapping.entries.single { it.key.owner == "sample/ValueApi" && it.key.name.startsWith("boxed-") }.value
    val describe = rename.methodMapping.entries.single { it.key.owner == "sample/ValueApi" && it.key.name.startsWith("describe-") }.value
    val explicit = rename.methodMapping.getValue(MemberKey("sample/ValueApi", "explicitLabel", "(Ljava/lang/String;)Ljava/lang/String;"))
    fun valueMember(owner: String, prefix: String) = rename.methodMapping.entries.single {
      it.key.owner == "sample/$owner" && it.key.name.startsWith(prefix)
    }.value
    val add = valueMember("Ticket", "add-")
    val raw = valueMember("Ticket", "getRaw")
    val doubled = valueMember("Ticket", "getDoubled-")
    val next = valueMember("Ticket", "getNext-")
    val consumer = dir.resolve("ValueConsumer.kt")
    writeSource(consumer, """
      fun run(): String {
        val api = $api()
        val first = $ticket(7).$add(2)
        val boxed: Any = first
        val nullable: $ticket? = api.$boxed(first)
        val values: List<$ticket> = listOf(boxed as $ticket)
        return api.$read(first).toString() + ":" + nullable!!.$doubled +
          ":" + values[0].$raw + ":" + api.$describe($label("ok")) + ":" + (first == $ticket(9)) + ":" + first.$next.$raw + ":" + api.$explicit($label("x"))
      }
    """.trimIndent())
    val output = dir.resolve("value-consumer")
    compile(consumer, output, jar)
    URLClassLoader(arrayOf(output.toUri().toURL(), jar.toUri().toURL()), javaClass.classLoader).use { loader ->
      assertEquals("9:18:9:ok!:true:10:x", loader.loadClass("ValueConsumerKt").getMethod("run").invoke(null))
      val type = loader.loadClass(api)
      val run = rename.methodMapping.getValue(MemberKey("sample/ValueApi", "run", "()I"))
      assertEquals(18, type.getMethod(run).invoke(type.getConstructor().newInstance()))
      val valueType = loader.loadClass(ticket)
      assertTrue(valueType.kotlin.isValue)
      val value = valueType.getMethod("box-impl", Int::class.javaPrimitiveType).invoke(null, 11)
      assertEquals(11, valueType.kotlin.memberProperties.single { it.name == raw }.getter.call(value))
      val added = valueType.kotlin.memberFunctions.single { it.name == add }.call(value, 2)
      assertEquals(13, valueType.getMethod("unbox-impl").invoke(added))
      assertTrue(valueType.declaredFields.none { it.name == "raw" }, "underlying JVM field may rename independently")
    }
  }
}
