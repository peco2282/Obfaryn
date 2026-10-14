package com.peco2282.obfaryn.core

import com.peco2282.obfaryn.core.context.ObfuscationContext
import com.peco2282.obfaryn.core.keep.KeepRules
import com.peco2282.obfaryn.core.rename.MemberKey
import com.peco2282.obfaryn.core.rename.RenameTransformer
import com.peco2282.obfaryn.core.transformer.VirtualizationTransformer
import com.peco2282.obfaryn.core.transformer.StringEncryptionTransformer
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.InvokeDynamicInsnNode
import org.objectweb.asm.tree.LdcInsnNode
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import java.util.Random
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import javax.tools.ToolProvider
import kotlin.io.path.writeText

class VirtualBridgeTest {
  @TempDir lateinit var dir: Path

  private fun fixture(): ObfuscationContext {
    val source = dir.resolve("BridgeSample.java")
    source.writeText("""
      public class BridgeSample extends BridgeBase implements BridgeFace {
        public int visible = 7;
        private int secret = 11;
        public static int global = 13;
        private Object reference;
        public int call(int n) { return n + 5; }
        private int hidden(int n) { return n * 2; }
        public static int twice(int n) { return n * 2; }
        public int read() { return visible + secret; }
        public int write(int n) { visible = n; secret = n + 1; return visible + secret; }
        public int statics(int n) { global = n; return global; }
        public Object references(Object n) { reference = n; return reference; }
        public int virtualCall(BridgeSample other, int n) { return other.call(n); }
        public int privateCall(int n) { return hidden(n); }
        public int staticCall(int n) { return twice(n); }
        public int interfaceCall(BridgeFace other, int n) { return other.call(n); }
        public int inheritedCall(int n) { return inherited(n); }
        public int specialCall(int n) { return super.inherited(n); }
        public Object cast(Object n) { return (BridgeFace)n; }
        public int instance(Object n) { return n instanceof BridgeFace ? 1 : 0; }
        public String concat(String value, int n) { return "prefix:" + value + ":" + n + ":suffix"; }
        public void voidCall(int n) { set(n); }
        public void set(int n) { visible = n; }
      }
      interface BridgeFace { int call(int n); }
      class BridgeBase { public int inherited(int n) { return n + 9; } }
    """.trimIndent())
    assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null,
      "--release", "17", "-d", dir.toString(), source.toString()))
    val nodes = linkedMapOf<String, ClassNode>()
    for (name in listOf("BridgeSample", "BridgeFace", "BridgeBase")) {
      val node = ClassNode()
      ClassReader(Files.readAllBytes(dir.resolve("$name.class"))).accept(node, 0)
      nodes[name] = node
    }
    return ObfuscationContext(nodes, linkedMapOf())
  }

  private fun jar(context: ObfuscationContext): Path {
    val path = dir.resolve("bridge.jar")
    JarOutputStream(Files.newOutputStream(path)).use { out ->
      for (node in context.classes.values) {
        val writer = ClassWriter(ClassWriter.COMPUTE_FRAMES or ClassWriter.COMPUTE_MAXS)
        node.accept(writer)
        out.putNextEntry(JarEntry("${node.name}.class")); out.write(writer.toByteArray()); out.closeEntry()
      }
    }
    return path
  }

  @Test fun fieldBridgesExecute() {
    val context = fixture()
    VirtualizationTransformer(listOf("BridgeSample\\.(read|write|statics|references)\\(.*"), Random(42)).transform(context)
    URLClassLoader(arrayOf(jar(context).toUri().toURL()), platformClassLoader()).use { loader ->
      val type = loader.loadClass("BridgeSample")
      val instance = type.getConstructor().newInstance()
      assertEquals(18, type.getMethod("read").invoke(instance))
      assertEquals(41, type.getMethod("write", Int::class.javaPrimitiveType).invoke(instance, 20))
      assertEquals(77, type.getMethod("statics", Int::class.javaPrimitiveType).invoke(instance, 77))
      val value = Any()
      assertSame(value, type.getMethod("references", Any::class.java).invoke(instance, value))
    }
  }

  @Test fun bridgesExecuteAndSurviveRenameAndRelocation() {
    for (rename in listOf(false, true)) for (seed in listOf(42L, 43L)) {
      val context = fixture()
      val selected = "read|write|statics|references|virtualCall|privateCall|staticCall|interfaceCall|inheritedCall|specialCall|cast|instance|concat|voidCall"
      VirtualizationTransformer(listOf("BridgeSample\\.($selected)\\(.*"), Random(seed), 2).transform(context)
      StringEncryptionTransformer(Random(seed + 100)).transform(context)
      val transformedInstructions = context.classes.values.flatMap { it.methods }.flatMap { it.instructions.toArray().toList() }
      assertFalse(transformedInstructions.any { it is InvokeDynamicInsnNode && it.bsm.owner == "java/lang/invoke/StringConcatFactory" })
      assertFalse(transformedInstructions.any { it is LdcInsnNode && (it.cst == "prefix:" || it.cst == ":suffix") })
      val renamer = RenameTransformer(ObfuscationConfig(targetPackage = "hidden", flattenPackages = true), KeepRules())
      if (rename) renamer.transform(context)
      val owner = if (rename) renamer.classMapping.getValue("BridgeSample") else "BridgeSample"
      fun methodName(name: String, descriptor: String) = if (rename)
        renamer.methodMapping[MemberKey("BridgeSample", name, descriptor)] ?: name else name
      URLClassLoader(arrayOf(dir.toUri().toURL()), platformClassLoader()).use { original ->
        URLClassLoader(arrayOf(jar(context).toUri().toURL()), platformClassLoader()).use { transformed ->
          val before = original.loadClass("BridgeSample")
          val after = transformed.loadClass(owner.replace('/', '.'))
          val a = before.getConstructor().newInstance()
          val b = after.getConstructor().newInstance()
          val integer = Int::class.javaPrimitiveType!!
          for (name in listOf("write", "statics", "privateCall", "staticCall", "inheritedCall", "specialCall")) {
            for (n in listOf(-4, 0, 23)) assertEquals(before.getMethod(name, integer).invoke(a, n),
              after.getMethod(methodName(name, "(I)I"), integer).invoke(b, n), name)
          }
          assertEquals(before.getMethod("read").invoke(a), after.getMethod(methodName("read", "()I")).invoke(b))
          val value = Any()
          assertSame(value, after.getMethod(methodName("references", "(Ljava/lang/Object;)Ljava/lang/Object;"), Any::class.java).invoke(b, value))
          assertSame(b, after.getMethod(methodName("cast", "(Ljava/lang/Object;)Ljava/lang/Object;"), Any::class.java).invoke(b, b))
          assertNull(after.getMethod(methodName("cast", "(Ljava/lang/Object;)Ljava/lang/Object;"), Any::class.java).invoke(b, null))
          val castError = assertThrows(java.lang.reflect.InvocationTargetException::class.java) {
            after.getMethod(methodName("cast", "(Ljava/lang/Object;)Ljava/lang/Object;"), Any::class.java).invoke(b, value)
          }
          assertInstanceOf(ClassCastException::class.java, castError.cause)
          for ((x, expected) in listOf(b to 1, value to 0, null to 0)) assertEquals(expected,
            after.getMethod(methodName("instance", "(Ljava/lang/Object;)I"), Any::class.java).invoke(b, x))
          assertEquals("prefix:value:7:suffix", after.getMethod(methodName("concat", "(Ljava/lang/String;I)Ljava/lang/String;"),
            String::class.java, integer).invoke(b, "value", 7))
          assertNull(after.getMethod(methodName("voidCall", "(I)V"), integer).invoke(b, 87))
          val visible = if (rename) renamer.fieldMapping[MemberKey("BridgeSample", "visible", "I")] ?: "visible" else "visible"
          assertEquals(87, after.getField(visible).getInt(b))
          assertEquals(12, after.getMethod(methodName("virtualCall", "(LBridgeSample;I)I"), after, integer).invoke(b, b, 7))
          val face = transformed.loadClass((if (rename) renamer.classMapping.getValue("BridgeFace") else "BridgeFace").replace('/', '.'))
          assertEquals(12, after.getMethod(methodName("interfaceCall", "(LBridgeFace;I)I"), face, integer).invoke(b, b, 7))
          val npe = assertThrows(java.lang.reflect.InvocationTargetException::class.java) {
            after.getMethod(methodName("virtualCall", "(LBridgeSample;I)I"), after, integer).invoke(b, null, 7)
          }
          assertInstanceOf(NullPointerException::class.java, npe.cause)
        }
      }
    }
  }
}
