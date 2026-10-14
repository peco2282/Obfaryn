package com.peco2282.obfaryn.core

import com.peco2282.obfaryn.core.jar.ClassHierarchyResolver
import com.peco2282.obfaryn.core.jar.JarReader
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarEntry
import java.util.jar.JarFile
import java.util.jar.JarOutputStream
import javax.tools.ToolProvider
import kotlin.io.path.readText
import kotlin.io.path.writeText

class HierarchyTest {
  @TempDir lateinit var dir: Path

  @Test fun dependencyHierarchyAndComputedFrames() {
    val depSrc = dir.resolve("dep-src/dep")
    Files.createDirectories(depSrc)
    depSrc.resolve("Base.java").writeText("package dep; public class Base { public String id() { return \"base\"; } }")
    depSrc.resolve("Left.java").writeText("package dep; public class Left extends Base { public String external() { return \"external\"; } }")
    depSrc.resolve("Right.java").writeText("package dep; public class Right extends Base {}")
    val depClasses = dir.resolve("dep-classes")
    compile(depSrc, depClasses)
    val dependency = dir.resolve("dependency.jar")
    pack(depClasses, dependency)

    val appSrc = dir.resolve("app-src/sample")
    Files.createDirectories(appSrc)
    appSrc.resolve("A.java").writeText("package sample; public class A extends dep.Left { @Override public String external() { return super.external(); } public String local() { return \"local\"; } }")
    appSrc.resolve("B.java").writeText("package sample; public class B extends dep.Right {}")
    appSrc.resolve("Choose.java").writeText("package sample; public class Choose { public static dep.Base choose(boolean flag) { dep.Base result; if (flag) result = new A(); else result = new B(); return result; } public static String useA() { A a = new A(); return a.local() + \":\" + a.external(); } }")
    val appClasses = dir.resolve("app-classes")
    compile(appSrc, appClasses, dependency)
    val input = dir.resolve("input.jar")
    pack(appClasses, input, "sample.A")

    ClassHierarchyResolver(JarReader().read(input), listOf(dependency)).use { hierarchy ->
      assertEquals("dep/Base", hierarchy.commonSuperClass("sample/A", "sample/B"))
      assertEquals("java/util/AbstractList", hierarchy.commonSuperClass("java/util/ArrayList", "java/util/LinkedList"))
      assertEquals("java/lang/Object", hierarchy.commonSuperClass("[Ljava/lang/String;", "[Ljava/lang/Integer;"))
    }
    ClassHierarchyResolver(JarReader().read(input), emptyList()).use { hierarchy ->
      val missing = assertThrows(IllegalStateException::class.java) {
        hierarchy.commonSuperClass("sample/A", "sample/B")
      }
      assertTrue(missing.message!!.contains("--classpath"))
    }
    val output = dir.resolve("output.jar")
    Obfuscator(ObfuscationConfig(
      seed = 2, classpath = listOf(dependency), recomputeFrames = true, verifyOutput = true,
      renameClasses = false, renameMethods = true, renameFields = false,
      stringEncryption = false, numberObfuscation = false,
    )).obfuscate(input, output).also { result ->
      val mapping = requireNotNull(result.mapping).readText()
      assertTrue(mapping.contains("sample/A.local()Ljava/lang/String; ->"))
      assertFalse(mapping.contains("sample/A.external()Ljava/lang/String; ->"))
    }
    val missingClasspathResult = Obfuscator(ObfuscationConfig(
      seed = 2, renameClasses = false, renameMethods = true, renameFields = false,
      stringEncryption = false, numberObfuscation = false,
    )).obfuscate(input, dir.resolve("without-dependency.jar"))
    assertFalse((missingClasspathResult.mapping?.readText() ?: "").contains("sample/A.local()Ljava/lang/String; ->"))
    URLClassLoader(arrayOf(output.toUri().toURL(), dependency.toUri().toURL()), ClassLoader.getSystemClassLoader().parent).use { loader ->
      val choose = loader.loadClass("sample.Choose").getMethod("choose", Boolean::class.javaPrimitiveType)
      assertEquals("sample.A", choose.invoke(null, true).javaClass.name)
      assertEquals("sample.B", choose.invoke(null, false).javaClass.name)
      assertEquals("local:external", loader.loadClass("sample.Choose").getMethod("useA").invoke(null))
    }
    JarFile(output.toFile()).use { jar -> assertNotNull(jar.getJarEntry("sample/Choose.class")) }
  }

  private fun compile(source: Path, output: Path, classpath: Path? = null) {
    Files.createDirectories(output)
    val files = Files.list(source).use { it.map(Path::toString).unmodifiableList() }
    val args = mutableListOf("--release", "17", "-d", output.toString())
    if (classpath != null) args += listOf("-classpath", classpath.toString())
    args += files
    assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, *args.toTypedArray()))
  }

  private fun pack(classes: Path, output: Path, paperMain: String? = null) {
    JarOutputStream(Files.newOutputStream(output)).use { jar ->
      Files.walk(classes).use { paths -> paths.filter(Files::isRegularFile).forEach { file ->
        jar.putNextEntry(JarEntry(classes.relativize(file).toString().replace('\\', '/')))
        jar.write(Files.readAllBytes(file)); jar.closeEntry()
      } }
      if (paperMain != null) {
        jar.putNextEntry(JarEntry("plugin.yml"))
        jar.write("name: HierarchyTest\nmain: $paperMain\n".toByteArray())
        jar.closeEntry()
      }
    }
  }
}
