package com.peco2282.obfaryn.platform.paper

import com.peco2282.obfaryn.api.ClassContributor
import com.peco2282.obfaryn.api.ClassSink
import com.peco2282.obfaryn.api.PlatformContext
import com.peco2282.obfaryn.api.PlatformOptions
import org.objectweb.asm.ClassReader
import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.ClassNode

internal class PaperCacheContributor : ClassContributor {
  override fun contribute(context: PlatformContext, options: PlatformOptions, sink: ClassSink) {
    val factories = context.classes.values.filter { owner ->
      (owner.visibleAnnotations.orEmpty() + owner.invisibleAnnotations.orEmpty()).any { it.desc == ANNOTATION }
    }
    if (factories.isEmpty()) return

    fun isSupplier(name: String, visited: MutableSet<String> = mutableSetOf()): Boolean {
      if (name == "java/util/function/Supplier") return true
      if (!visited.add(name)) return false
      val owner = context.classes[name] ?: return false
      return (listOfNotNull(owner.superName) + owner.interfaces.orEmpty()).any { isSupplier(it, visited) }
    }

    for (factory in factories) {
      require(factory.access and Opcodes.ACC_PUBLIC != 0 &&
          factory.access and (Opcodes.ACC_ABSTRACT or Opcodes.ACC_INTERFACE) == 0) {
        "@PaperCache requires a public concrete factory: ${factory.name}"
      }
      require(isSupplier(factory.name)) {
        "@PaperCache factory must implement Supplier through classes present in the input: ${factory.name}"
      }
      require(factory.methods.any { it.name == "<init>" && it.desc == "()V" && it.access and Opcodes.ACC_PUBLIC != 0 }) {
        "@PaperCache factory requires a public no-argument constructor: ${factory.name}"
      }
    }

    val runtime = linkedMapOf<String, ByteArray>()
    fun readRuntime(name: String) {
      if (name in runtime) return
      val bytes = checkNotNull(javaClass.getResourceAsStream("/$name.class")) {
        "Missing compiled Paper cache runtime: $name"
      }.use { it.readBytes() }
      runtime[name] = bytes
      val node = ClassNode()
      ClassReader(bytes).accept(node, ClassReader.SKIP_CODE or ClassReader.SKIP_DEBUG or ClassReader.SKIP_FRAMES)
      node.innerClasses.map { it.name }.filter { it.startsWith("$RUNTIME$") }.sorted().forEach(::readRuntime)
    }
    readRuntime(RUNTIME)
    require(runtime.keys.none { it in context.classes }) {
      "Paper cache runtime conflicts with input classes; use a compile-only Paper platform dependency"
    }
    if (ANNOTATION_NAME !in context.classes) readRuntime(ANNOTATION_NAME)
    runtime.values.forEach(sink::add)
  }

  private companion object {
    const val ANNOTATION_NAME = "com/peco2282/obfaryn/platform/paper/annotation/PaperCache"
    const val ANNOTATION = "L$ANNOTATION_NAME;"
    const val RUNTIME = "com/peco2282/obfaryn/platform/paper/cache/PaperCaches"
  }
}
