package com.peco2282.obfaryn.api.protection

import com.peco2282.obfaryn.api.transformer.TransformationPolicy
import com.peco2282.obfaryn.api.transformer.Transformer
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.*
import java.net.URLClassLoader
import java.nio.file.*
import javax.tools.ToolProvider

class BinaryPolicyCompatibilityTest {
  @TempDir lateinit var dir: Path

  @Test fun implementationsCompiledAgainstOldPolicyInheritNewJvmDefaults() {
    val api = dir.resolve("TransformationPolicy.java")
    val implementation = dir.resolve("LegacyPolicy.java")
    Files.write(api, """
      package com.peco2282.obfaryn.api.transformer;
      import org.objectweb.asm.tree.*;
      public interface TransformationPolicy {
        boolean canTransformBody(ClassNode c, MethodNode m);
        boolean canTransformMetadata(ClassNode c);
        boolean canTransformConstant(ClassNode c, FieldNode f);
      }
    """.trimIndent().toByteArray())
    Files.write(implementation, """
      package legacy;
      import com.peco2282.obfaryn.api.transformer.TransformationPolicy;
      import org.objectweb.asm.tree.*;
      public class LegacyPolicy implements TransformationPolicy {
        public boolean canTransformBody(ClassNode c, MethodNode m) { return true; }
        public boolean canTransformMetadata(ClassNode c) { return true; }
        public boolean canTransformConstant(ClassNode c, FieldNode f) { return true; }
      }
    """.trimIndent().toByteArray())
    val classpath = listOf(ClassNode::class.java, Opcodes::class.java).map {
      java.nio.file.Paths.get(it.protectionDomain.codeSource.location.toURI()).toString()
    }.joinToString(java.io.File.pathSeparator)
    assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null,
      "--release", "8", "-classpath", classpath, "-d", dir.toString(), api.toString(), implementation.toString()))
    // Parent-first loading uses today's API, while LegacyPolicy has only the old three methods.
    URLClassLoader(arrayOf(dir.toUri().toURL()), TransformationPolicy::class.java.classLoader).use { loader ->
      val policy = loader.loadClass("legacy.LegacyPolicy").getConstructor().newInstance() as TransformationPolicy
      val owner = ClassNode()
      val method = MethodNode()
      assertTrue(policy.canTransformBody(owner, method, BodyFeature.STRINGS))
      assertNull(policy.exclusionReason(owner, method, BodyFeature.CALLS))
      assertEquals(ControlFlowMode.INHERIT, policy.controlFlowMode(owner, method))
      assertEquals(TransformationBudget(), policy.budget(owner, method))
    }
    assertTrue(Transformer::class.java.getMethod("getIsolatedBodyFeature").isDefault)
    assertTrue(Transformer::class.java.getMethod("skipReason", com.peco2282.obfaryn.api.ObfuscationContext::class.java,
      ClassNode::class.java, MethodNode::class.java).isDefault)
    assertNotNull(Class.forName("com.peco2282.obfaryn.api.transformer.TransformationPolicy\$DefaultImpls"))
  }
}
