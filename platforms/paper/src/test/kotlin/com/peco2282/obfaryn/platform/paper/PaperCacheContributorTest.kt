package com.peco2282.obfaryn.platform.paper

import com.peco2282.obfaryn.api.MapPlatformOptions
import com.peco2282.obfaryn.api.PlatformContext
import com.peco2282.obfaryn.api.context.ResourceEntry
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.objectweb.asm.ClassReader
import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.AnnotationNode
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.MethodNode

class PaperCacheContributorTest {
  private val annotation = "Lcom/peco2282/obfaryn/platform/paper/annotation/PaperCache;"
  private val runtime = "com/peco2282/obfaryn/platform/paper/cache/PaperCaches"

  private fun factory(visible: Boolean = false) = ClassNode().apply {
    name = "sample/Table"
    access = Opcodes.ACC_PUBLIC
    superName = "java/lang/Object"
    interfaces.add("java/util/function/Supplier")
    methods.add(MethodNode(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null))
    if (visible) visibleAnnotations = mutableListOf(AnnotationNode(annotation))
    else invisibleAnnotations = mutableListOf(AnnotationNode(annotation))
  }

  private fun contribute(vararg nodes: ClassNode): Map<String, ByteArray> {
    val output = linkedMapOf<String, ByteArray>()
    val context = object : PlatformContext {
      override val classes = nodes.associateBy { it.name }
      override val resources = emptyMap<String, ResourceEntry>()
    }
    PaperCacheContributor().contribute(context, MapPlatformOptions()) { bytes ->
      assertNull(output.put(ClassReader(bytes).className, bytes))
    }
    return output
  }

  @Test
  fun doesNotContributeWithoutAnnotation() {
    assertTrue(contribute().isEmpty())
    assertTrue(contribute(factory().apply { invisibleAnnotations.clear() }).isEmpty())
  }

  @Test
  fun contributesCompleteRuntimeForVisibleAndInvisibleAnnotations() {
    for (visible in listOf(false, true)) {
      val node = factory(visible).apply {
        visibleAnnotations = visibleAnnotations.orEmpty().toMutableList().apply { add(AnnotationNode("Lsample/Keep;")) }
        invisibleAnnotations = invisibleAnnotations.orEmpty().toMutableList().apply { add(AnnotationNode("Lsample/Hidden;")) }
      }
      val classes = contribute(node)
      assertTrue(runtime in classes)
      assertTrue(classes.keys.any { it.startsWith("$runtime$") })
      assertFalse(annotation.substring(1, annotation.length - 1) in classes)
      assertEquals(listOf("Lsample/Keep;"), node.visibleAnnotations.map { it.desc })
      assertEquals(listOf("Lsample/Hidden;"), node.invisibleAnnotations.map { it.desc })
      val loader = object : ClassLoader(null) {
        override fun findClass(name: String): Class<*> {
          val bytes = classes[name.replace('.', '/')] ?: throw ClassNotFoundException(name)
          return defineClass(name, bytes, 0, bytes.size)
        }
      }
      val helper = loader.loadClass(runtime.replace('/', '.'))
      helper.getMethod("invalidate", Class::class.java).invoke(null, String::class.java)
    }
  }

  @Test
  fun supportsSupplierInheritedFromAnInputClass() {
    val base = factory().apply {
      name = "sample/Base"
      invisibleAnnotations.clear()
    }
    val child = factory().apply {
      superName = base.name
      interfaces.clear()
    }
    assertTrue(runtime in contribute(base, child))
  }

  @Test
  fun rejectsInvalidFactoriesBeforeContributingAnything() {
    val invalid = listOf(
      factory().apply { access = 0 },
      factory().apply { access = Opcodes.ACC_PUBLIC or Opcodes.ACC_ABSTRACT },
      factory().apply { access = Opcodes.ACC_PUBLIC or Opcodes.ACC_INTERFACE },
      factory().apply { interfaces.clear() },
      factory().apply { methods.clear() },
      factory().apply { methods.single().access = Opcodes.ACC_PRIVATE },
      factory().apply { superName = name; interfaces.clear() },
    )
    for (node in invalid) {
      val error = assertThrows(IllegalArgumentException::class.java) { contribute(node) }
      assertTrue(error.message!!.contains("@PaperCache"))
      assertTrue(error.message!!.contains(node.name))
    }
  }

  @Test
  fun rejectsRuntimeCollisionsAndReusesAnExistingAnnotationClass() {
    val collision = ClassNode().apply { name = runtime }
    assertThrows(IllegalArgumentException::class.java) { contribute(factory(), collision) }
    val marker = ClassNode().apply { name = annotation.substring(1, annotation.length - 1) }
    val classes = contribute(factory(), marker)
    assertTrue(runtime in classes)
    assertFalse(marker.name in classes)
  }
}